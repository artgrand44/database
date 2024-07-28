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

if [ "$#" -ge 4 ]; then
    replicas=$4
fi

if [ -z "${replicas}" ]; then 
	read -p "Replicas?[1]" replicas


	if [ -z "${replicas}" ]; then 
		shard="1"
	fi
fi

if [ "$#" -ge 5 ]; then
    size=$5
fi

if [ -z "${size}" ]; then 
	read -p "Size?[4Gi]" size


	if [ -z "${size}" ]; then 
		size="4Gi"
	fi
fi

if [ "$#" -ge 6 ]; then
    label=$6
fi

if [ -z "${label}" ]; then 
	read -p "Node label?["kubernetes.io/hostname]" label


	if [ -z "${label}" ]; then 
		label="kubernetes.io/hostname"
	fi
fi

if [ "$#" -ge 7 ]; then
    value=$7
fi

if [ -z "${value}" ]; then 
	read -p "Value?["minikube]" label


	if [ -z "${value}" ]; then 
		value="minikube"
	fi
fi

cp sample.yaml temp.yaml
ls temp.yaml

echo ""
echo ""

sed -e s/"servicename"/"$service"/g  -e  s/"shardname"/"$shard"/g -e s/"databasename"/"$database"/g  temp.yaml > custom.yaml

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