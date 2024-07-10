# To run this file:
# 
# cd /Users/artgrand/repros/database
# docker build . --tag db:v1
# docker run db 
# To create the Kunernetes service kubectl create -f deployment.yaml
# To make it visable from desktop kubectl expose deployment helloworld --type=NodePort
# to delete deployment kubectl delete services/artdb then kubectl delete deployment.apps/artdb-deployment
# to create deployment kubectl create -f deployment.yaml
#  kubectl expose deployment artdb-deployment  --type=NodePort

# eval $(minikube -p minikube docker-env)
# minikube start
# docker build . --tag db:v1 
# kubectl create -f deployment.yaml
# kubectl expose service/artdb --type=NodePort --port=8080
# minikube service my-serivce

# to expose the url: kubectl expose  deployment.apps/artdb-deployment
# kubectl expose  deployment.apps/artdb-deployment --type=LoadBalancer --name=dbservice
# kubectl expose  deployment.apps/artdb-deployment --type=NodePort  --name=artdb1
# kubectl expose pod "artdb-deployment-7687844b98-qnl5r" --type=NodePort --port=8080 


# To describe the service kubectl describe services/artdb  

FROM arm64v8/openjdk:22

WORKDIR /app

COPY dist/server-0.0.1-SNAPSHOT-fat.jar /app

CMD ["java", "-cp", "server-0.0.1-SNAPSHOT-fat.jar", "org.art.db.server.Server"]