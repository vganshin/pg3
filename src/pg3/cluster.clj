(ns pg3.cluster
  (:require [clj-yaml.core :as yaml]
            [k8s.core :as k8s]
            [clojure.string :as str]
            [pg3.naming :as naming]
            [pg3.model :as model]
            [cheshire.core :as json]
            [pg3.utils :as ut]
            [pg3.fsm :as fsm]
            [unifn.core :as u]))

;; watch new clusters
;; orchestarate setup

;; watch status of clusters
;; based on instances state

(defmethod u/*fn ::load-pg-instances [{cluster :resource}]
  {::ut/pginstances (ut/pginstances (get-in cluster [:metadata :namespace]) (naming/service-name (naming/resource-name cluster)))})

(defmethod u/*fn ::load-random-colors [arg]
  (let [colors (take 2 (shuffle naming/colors))]
    {::colors {:master (first colors)
               :replica (second colors)}}))

#_(defmethod u/*fn :k8s/patch [{path :k8s/path :as arg}]
    (let [result (k8s/patch (get-in arg path))]
      (when (= (:kind result) "Status")
        {::u/status :error
         ::u/message (str result)})))

#_(defmethod u/*fn :k8s/create [{path :k8s/path :as arg}])

(defn strict-patch [resource]
  (let [result (k8s/patch resource)]
    (when (= (:kind result) "Status")
      {::u/status :error
       ::u/message (str result)})))

(defmethod u/*fn ::ensure-cluster-config [{cluster :resource}]
  (strict-patch (model/config-map cluster)))

(defmethod u/*fn ::ensure-cluster-secret [{cluster :resource}]
  (strict-patch (model/secret cluster)))

(defmethod u/*fn ::ensure-cluster-backup-item [{cluster :resource backup-item ::backup-item}]
  (strict-patch (model/backup-spec cluster backup-item)))

(defmethod u/*fn ::ensure-cluster-backup [{cluster :resource :as arg}]
  (dissoc
   (u/*apply (->> (:backup cluster)
                  (mapv (fn [item] {::u/fn ::ensure-cluster-backup-item
                                    ::backup-item item})))
             arg)
   ::backup-item))

(defmethod u/*fn ::ensure-instance [{role ::role cluster :resource :as arg}]
  (let [instance (get-in arg [::ut/pginstances role])
        color (get-in arg [::colors role])
        instance (or instance (model/instance-spec cluster color (name role)))]
    (strict-patch instance)))

(defn load-pods [cluster]
  (let [ns (get-in cluster [:metadata :namespace])
        service-name (naming/service-name (naming/resource-name cluster))
        pods (:items (k8s/query {:apiVersion "v1"
                                 :kind "pod"
                                 :ns ns}
                                {:labelSelector (format "service=%s,type=instance" service-name)}))]
    (->> pods
         (map (fn [pod]
                [(keyword (get-in pod [:metadata :labels :role])) pod]))
         (into {}))))

(defmethod u/*fn ::load-pods [{cluster :resource}]
  {::pods (load-pods cluster)})

(defmethod u/*fn ::pod-running? [{role ::role pods ::pods errors ::errors :or {errors []}}]
  (let [pod (get pods role)
        running? (= (get-in pod [:status :phase]) "Running")
        ok? (ut/resource-ok? pod)]
    (cond
      (and running? ok?)
      {::pod pod}

      (not pod)
      {::errors (conj errors (str (str/capitalize (name role)) " • Pod does not exists"))}

      :else
      {::errors (cond-> errors
                  (not running?) (conj (str (str/capitalize (name role)) " • Pod is not running"))
                  (not ok?)      (concat (mapv (fn [err]
                                                 (str (str/capitalize (name role)) " • " err))
                                               (ut/resource-errors pod))))})))

(defmethod u/*fn ::check-instance-disk [{pod ::pod errors ::errors :or {errors []}}]
  (when pod
    (let [cmd {:executable "/bin/bash"
               :args ["-c" "df -h /data | awk '{print $5}' | grep -oE \\\\d+"]}
          {status :status message :message} (k8s/exec pod cmd "pg")
          role (str/capitalize (get-in pod [:metadata :labels :role]))]
      (cond
        (and (= status :succeed)
             (>= (ut/read-int message) 90))
        {::errors (conj errors (format "%s • Low disk space: %d%%" role (ut/read-int message)))}

        (= status :failure)
        {::errors (conj errors (format "%s • %s" role message))}))))

(defmethod u/*fn ::check-postgres [{pod ::pod errors ::errors :or {errors []}}]
  (when pod
    (let [cmd {:executable "psql"
               :args ["-c" "select 1;"]}
          {status :status message :message} (k8s/exec pod cmd "pg")
          role (str/capitalize (get-in pod [:metadata :labels :role]))]
      (when (= status :failure)
        {::errors (conj errors (format "%s • Postgresql not available: %s" role message))}))))

(defmethod u/*fn ::check-replication-status [{pod ::pod errors ::errors :or {errors []}}]
  (when pod
    (let [cmd {:executable "psql"
               :args ["-qtAX" "-c" "select count(*) from pg_stat_replication;"]}
          {status :status message :message} (k8s/exec pod cmd "pg")
          role (str/capitalize (get-in pod [:metadata :labels :role]))]
      (cond (and (= status :succeed) (< (ut/read-int message) 1))
            {::errors (conj errors (format "%s • There is no any alive replica" role))}
            (= status :failure)
            {::errors (conj errors (format "%s • %s" role message))}))))

(defmethod u/*fn ::calculate-monitoring-result
  [{{{failed-with-error :monitoring-failed-with-error} :status} :resource errors ::errors}]
  (cond
    (not (empty? errors)) {::u/status :error
                           ::u/message (str "\n" (str/join "\n" errors))}
    failed-with-error {::u/status :success
                       ::u/message "Cluster recovered after a monitoring error"
                       :status-data {:monitoring-failed-with-error false}}
    :else {}))

(defmethod u/*fn ::set-monitoring-error-flag [_]
  {:status-data {:monitoring-failed-with-error true}
   ::u/status :success})

(def fsm-main
  {:init {:action-stack [{::u/fn ::ut/success
                          ::ut/message "Starting initialization..."}]
          :success :start-init}
   :start-init {:action-stack [::ensure-cluster-config
                               ::ensure-cluster-secret
                               ::ensure-cluster-backup
                               ::load-pg-instances
                               ::load-random-colors
                               {::u/fn ::ensure-instance ::role :master}
                               {::u/fn ::ensure-instance ::role :replica}
                               {::u/fn ::ut/success}]
                :success :waiting-initialization
                :error :error-state}
   :waiting-initialization {:action-stack [::load-pg-instances
                                           {::u/fn ::ut/cluster-active?}
                                           {::u/fn ::ut/success
                                            ::ut/message "Cluster was successfully initialized. Starting monitoring..."}]
                            :success :monitoring
                            :error :error-state}
   :monitoring {:action-stack [::load-pods
                               {::u/fn ::pod-running? ::role :master}
                               {::u/fn ::check-instance-disk}
                               {::u/fn ::check-postgres}
                               {::u/fn ::check-replication-status}
                               {::u/fn ::pod-running? ::role :replica}
                               {::u/fn ::check-instance-disk}
                               {::u/fn ::check-postgres}
                               ::calculate-monitoring-result]
                :success :monitoring
                :error :monitoring-error}
   :monitoring-error {:action-stack [::set-monitoring-error-flag]
                      :success :monitoring}
   :error-state {}})

(defn watch []
  (doseq [cluster (:items (k8s/query {:kind naming/cluster-resource-kind
                                      :apiVersion naming/api}))]
    (fsm/process-state fsm-main cluster)))

(comment
  (watch))
