#! /bin/bash

mvn clean package  -DskipTests=true -q
kubectl delete deployment.apps/tester deployment.apps/secondary  deployment.apps/replication  service/tester   service/secondary  service/replication
kubectl delete deployment.apps/tester service/tester
docker build . --tag db:v1
docker build . -f ./DockerFileTest --tag tester:v1 

kubectl apply -f roles.yaml

kubectl create -f tester.yaml
kubectl expose deployment tester
sleep 10
kubectl logs service/tester --tail -1 -f