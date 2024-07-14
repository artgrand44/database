#! /bin/bash
kubectl delete deployment.apps/helloworld service/helloworld
mvn clean package  -DskipTests=true
docker build . --tag db:v1
kubectl create -f helloworld.yaml
kubectl expose deployment helloworld --type=NodePort --port=8080
sleep 5
minikube service helloworld hello-minikube1 --url