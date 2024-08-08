#! /bin/bash

mvn clean package  -DskipTests=true -q

kubectl delete deployment.apps/tester service/tester
docker build . -f ./DockerFileTest --tag tester:v1 

kubectl create -f tester.yaml
kubectl expose deployment tester