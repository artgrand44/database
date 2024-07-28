#! /bin/bash


if [ "$#" -ge 1 ]; then
    service=$1
fi

if [ "$#" -ge 2 ]; then
    database=$2
fi



if [ -z "${database}" ]; then 
	read -p "Database?[database]" database


	if [ -z "${database}" ]; then 
		database="default"
	fi
fi
echo "database is $database"

if [ "$#" -ge 3 ]; then
    shard=$3
fi

if [ -z "${shard}" ]; then 
	read -p "Shard?[database]" shard


	if [ -z "${shard}" ]; then 
		shard="shard1"
	fi
fi
echo "Shard is $shard"


cp sample.yaml temp.yaml
ls temp.yaml

echo ""
echo ""

sed -e s/"servicename"/"$service"/g  -e  s/"shardname"/"$shard"/g -e s/"databasename"/"$database"/g  temp.yaml |tee custom.yaml

echo ""
echo ""


mvn clean package  -DskipTests=true -q
kubectl delete deployment.apps/${service} service/${service}
docker build . --tag db:v1
kubectl apply -f volume.yaml
kubectl create -f custom.yaml
kubectl expose deployment ${service} --type=NodePort --port=$port


sleep 5
minikube service ${service} hello-minikube1 --url