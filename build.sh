#! /bin/bash

#build.sh service1 db2 shard1 1 4Gi kubernetes.io\\/hostname minikube 1w 2

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

if [ "$#" -ge 3 ]; then
    shard=$3
fi

if [ -z "${shard}" ]; then 
	read -p "Shard?[database]" shard


	if [ -z "${shard}" ]; then 
		shard="shard1"
	fi
fi

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

if [ "$#" -ge 8 ]; then
    theint=$8
fi

if [ -z "${theint}" ]; then 
	read -p Backup interval?["1w]" theint


	if [ -z "${value}" ]; then 
		theint="1w"
	fi
fi

if [ "$#" -ge 9 ]; then
    maxbackup=$9
fi

if [ -z "${maxbackup}" ]; then 
	read -p Nax. saved backups?["2]" maxbackup


	if [ -z "${value}" ]; then 
		maxbackup="12"
	fi
fi
diskname="$database""-""$shard"
backupname="${diskname}""-back" 
sed -e s/"volumname"/"$diskname"/g  -e  s/"volumesize"/"$size"/g -e  s/"thelabel"/"$label"/g -e  s/"backupname"/"$backupname"/g volume.yaml >customvolume.yaml
sed -e s/"servicename"/"$service"/g  -e  s/"shardname"/"$shard"/g -e s/"databasename"/"$database"/g -e s/"reps"/"${replicas}"/g -e s/"volumname"/"$diskname"/g -e s/"theint"/"$theint"/g -e s/"maxbackup"/"$maxbackup"/g  sample.yaml > custom.yaml

echo ""
echo "Starting build..."


mvn clean package  -DskipTests=true -q
kubectl delete deployment.apps/${service} service/${service} service/my-service
docker build . --tag db:v1
kubectl apply -f roles.yaml
kubectl apply -f customvolume.yaml
kubectl create -f custom.yaml
kubectl expose deployment ${service} --type=LoadBalancer --name=${service}   