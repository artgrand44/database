# To run this file:
# 
# cd /Users/artgrand/repros/database
# 

# eval $(minikube -p minikube docker-env)
# minikube start
# docker build . --tag db:v1 
# 

#kubectl create clusterrolebinding default-pod --clusterrole cluster-admin --serviceaccount=default:default
# kubectl create -f helloworld.yaml
# $ kubectl expose deployment helloworld --type=NodePort
# $ minikube service helloworld

FROM arm64v8/openjdk:22

WORKDIR /app

COPY dist/server-0.0.1-SNAPSHOT-fat.jar /app

CMD ["java", "-cp", "server-0.0.1-SNAPSHOT-fat.jar", "org.art.db.server.Server"]