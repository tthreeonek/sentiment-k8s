#!/bin/sh
# Настройка одноузлового Minikube под слабую VPS (1 vCPU, 2 ГБ RAM).
# На машине с 4 CPU / 8 ГБ этот скрипт не нужен.

# 1. Уменьшение CPU requests static-подов control plane и ослабление их liveness-проб
#    (под нагрузкой ответы медленные, лишние рестарты apiserver только усиливают нагрузку)
minikube ssh -- "sudo sed -i \
  -e \"s/cpu: 250m/cpu: 50m/\" /etc/kubernetes/manifests/kube-apiserver.yaml && \
  sudo sed -i -e \"s/cpu: 200m/cpu: 30m/\" /etc/kubernetes/manifests/kube-controller-manager.yaml && \
  sudo sed -i -e \"s/cpu: 100m/cpu: 20m/\" /etc/kubernetes/manifests/kube-scheduler.yaml && \
  sudo sed -i -e \"s/cpu: 100m/cpu: 30m/\" /etc/kubernetes/manifests/etcd.yaml && \
  sudo sed -i -e \"s/failureThreshold: 8/failureThreshold: 30/\" -e \"s/timeoutSeconds: 15/timeoutSeconds: 30/\" \
     /etc/kubernetes/manifests/kube-apiserver.yaml /etc/kubernetes/manifests/kube-controller-manager.yaml \
     /etc/kubernetes/manifests/kube-scheduler.yaml /etc/kubernetes/manifests/etcd.yaml"
# kubelet перезапускает static-поды control plane — ждём, пока API снова станет доступен
until kubectl get --raw /readyz >/dev/null 2>&1; do sleep 5; done

# 2. CPU requests аддонов
kubectl -n kube-system set resources deployment coredns --requests=cpu=20m
kubectl -n kube-system set resources deployment metrics-server --requests=cpu=20m,memory=64Mi
kubectl -n kube-system set resources daemonset kindnet --requests=cpu=20m
kubectl -n ingress-nginx set resources deployment ingress-nginx-controller --requests=cpu=30m

# 3. Ненужный аддон (PVC в проекте не используются)
minikube addons disable storage-provisioner
