#! /bin/bash
./cleanup.sh
kubectl apply -f defaultvolume.yaml
kubectl apply -f defaultconfig.yaml
kubectl apply -f defaultset.yaml
