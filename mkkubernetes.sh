#! /bin/bash
kubectl delete deployment.apps/artdb service/artdb
mvn clean package  -DskipTests=true
docker build . --tag db:v1
kubectl create -f deployment.yaml
kubectl expose deployment artdb --type=NodePort --port=8080
sleep 5
minikube service artdb hello-minikube1 --url