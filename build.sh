#! /bin/bash
if [ "$#" -ge 1 ]; then
    database=$1
fi



if [ -z "${database}" ]; then 
	read -p "Database?[database]" database


	if [ -z "${database}" ]; then 
		database="default"
	fi
fi
echo "database is $database"

if [ "$#" -ge 2 ]; then
    shard=$2
fi

if [ -z "${shard}" ]; then 
	read -p "Shard?[database]" shard


	if [ -z "${shard}" ]; then 
		shard="shard1"
	fi
fi
echo "Shard is $shard"

if [ "$#" -ge 3 ]; then
    port=$3
fi

if [ -z "${port}" ]; then 
	read -p "Port?[8080]" shard


	if [ -z "${port}" ]; then 
		port="8080"
	fi
fi
echo "***********Port is $port"
cp sample.yaml temp.yaml
ls temp.yaml

echo ""
echo ""

sed  s/"shardname"/"$shard"/g temp.yaml |tee out1.yaml
sed  s/"databasename"/"$database"/g out1.yaml |tee out2.yaml
sed  s/"cport"/"$port"/g out2.yaml |tee out3.yaml

echo ""
echo ""


mvn clean package  -DskipTests=true -q
kubectl delete deployment.apps/${database} service/${database}
docker build . --tag db:v1
kubectl create -f out3.yaml
kubectl expose deployment ${database} --type=NodePort --port=$port

rm out*.yaml

sleep 5
minikube service ${database} hello-minikube1 --url