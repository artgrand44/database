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
	read -p "Size?[1]" size


	if [ -z "${size}" ]; then 
		size="4Gi"
	fi
fi

diskname="$database""-""$shard"


cp sample.yaml temp.yaml
ls temp.yaml

echo ""
echo ""

mnt=""
vols="        - name: dbindex\n"
mountPathBase="/data/files/root"


echo "Loop"

for ((num = 1; num <= "${replicas}"; num++))
do
	mnt="${mnt}""            \\- name: ""${diskname}"-"${num}""\n              mountPath: \/data\/files\/root""${num}""\n"
	vols="${vols}""        \\- name: ""${diskname}"-"${num}""\n"
	sed -e s/"volumname"/"$diskname""$num"/g  -e  s/"volumesize"/"$size"/g volume.yaml |tee customvolume.yaml
	kubectl apply -f customvolume.yaml

done
echo "mnts"
echo "${mnt}"
echo "volumes"
echo "${vols}"


echo "run sed"
sed -e s/"servicename"/"$service"/g  -e  s/"shardname"/"$shard"/g -e s/"databasename"/"$database"/g  -e s/"volumename"/"$diskname"/g -e s/"mnt"/"$mnt"/ -e s/"vols"/"$vols"/ temp.yaml |tee custom.yaml

echo "***Starting build"

mvn clean package  -DskipTests=true -q
kubectl delete deployment.apps/${service} service/${service}
docker build . --tag db:v1
kubectl apply -f indexvolume.yaml
kubectl create -f custom.yaml
kubectl expose deployment ${service} --type=NodePort --port=$port


sleep 5
minikube service ${service} hello-minikube1 --url
