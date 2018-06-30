# Postgres cluster controller for kubernetes [![Build Status](https://travis-ci.org/HealthSamurai/pg3.svg?branch=master)](https://travis-ci.org/HealthSamurai/pg3)

Kubernetes custom controller for HA PostgreSQL

## Architecture

![pg3](https://github.com/niquola/pg3/raw/master/doc/pg3.png)

## Installation

```sh
kubectl apply deplyment.yaml
```

## Development

### Using minikube

At first you have to upload [healthsamurai/backup-pg3:latest](https://github.com/HealthSamurai/backup-pg3/tree/master/base-image) image to Minikube's docker. If you don't know how to use local docker images with Minikube, see [the question on StackOverflow](https://stackoverflow.com/questions/42564058/how-to-use-local-docker-images-with-minikube).

Mock or configure [Telegram integration](https://github.com/HealthSamurai/pg3/blob/master/src/pg3/telegram.clj#L9-L15).

To cleanup cluster you can use this command  
```
kubectl -n pg3 delete pgbackups --all && kubectl delete pgcluster -n pg3 --all && kubectl delete pginstance -n pg3 --all  && kubectl delete all -n pg3 -l system=pg3 && kubectl delete -n pg3 persistentvolumeclaim --all
```


## License

Copyright © 2017 Health-Samurai

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
