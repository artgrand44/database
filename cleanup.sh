#! /bin/bash

kubectl delete statefulset `kubectl get statefulset --no-headers -o custom-columns=":metadata.name"`
kubectl delete pvc `kubectl get pvc --no-headers -o custom-columns=":metadata.name"`
kubectl delete pv `kubectl get pv --no-headers -o custom-columns=":metadata.name"`