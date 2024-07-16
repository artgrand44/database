#! /bin/bash
if [ "$#" -ge 1 ]; then
    cluster=$1
fi



if [ -z "${cluster}" ]; then 
	read -p "Cluster?[cluster]" cluster


	if [ -z "${cluster}" ]; then 
		cluster="default"
	fi
fi
echo "Cluster is $cluster"

if [ "$#" -ge 2 ]; then
    shard=$2
fi

if [ -z "${shard}" ]; then 
	read -p "Shard?[cluster]" shard


	if [ -z "${shard}" ]; then 
		echo "blank"
		shard="shard1"
	fi
fi
echo "Shard is $shard"
cp template.yaml temp.yaml
ls temp.yaml

echo ""
echo ""

set -x
sed  s/"shardname"/"$shard"/g temp.yaml |tee out1.yaml
sed  s/"clustername"/"$cluster"/g out1.yaml |tee out2.yaml

echo ""
echo ""


mvn clean package  -DskipTests=true
kubectl delete deployment.apps/artdb service/artdb
docker build . --tag db:v1
kubectl create -f out2.yaml
kubectl expose deployment artdb --type=NodePort --port=8080
sleep 5
minikube service artdb hello-minikube1 --url