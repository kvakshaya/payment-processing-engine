# Kubernetes Deployment — Payment Processing Engine

This folder contains Kubernetes manifests to deploy the full Payment Processing Engine stack
on a local Minikube cluster.

## Prerequisites

- [Minikube](https://minikube.sigs.k8s.io/docs/start/) installed
- [kubectl](https://kubernetes.io/docs/tasks/tools/) installed
- Docker installed

---

## Deploy on Minikube

### 1. Start Minikube
```bash
minikube start
```

### 2. Point Docker to Minikube's daemon (so it can see your local image)
```bash
eval $(minikube docker-env)
```

### 3. Build the app image inside Minikube
```bash
docker build -t payment-engine:latest .
```

### 4. Apply all manifests in order
```bash
kubectl apply -f k8s/00-namespace.yml
kubectl apply -f k8s/01-configmap.yml
kubectl apply -f k8s/02-secret.yml
kubectl apply -f k8s/03-postgres.yml
kubectl apply -f k8s/04-redis.yml
kubectl apply -f k8s/05-kafka.yml
kubectl apply -f k8s/06-app.yml
```

Or apply the whole folder at once:
```bash
kubectl apply -f k8s/
```

### 5. Check pods are running
```bash
kubectl get pods -n payment-engine
```

### 6. Access the app
```bash
minikube service payment-engine-service -n payment-engine
```
Or directly at: `http://$(minikube ip):30080`

---

## Useful Commands

```bash
# Watch pod status
kubectl get pods -n payment-engine -w

# View app logs
kubectl logs -f deployment/payment-engine -n payment-engine

# Describe a pod (for debugging)
kubectl describe pod <pod-name> -n payment-engine

# Delete everything
kubectl delete namespace payment-engine
```

---

## Manifest Overview

| File | What it does |
|---|---|
| `00-namespace.yml` | Creates isolated `payment-engine` namespace |
| `01-configmap.yml` | Non-sensitive config (DB name, Kafka/Redis hostnames) |
| `02-secret.yml` | Sensitive credentials (DB password, JWT secret) — base64 encoded |
| `03-postgres.yml` | PostgreSQL Deployment + PersistentVolumeClaim + Service |
| `04-redis.yml` | Redis Deployment + Service |
| `05-kafka.yml` | Zookeeper + Kafka Deployments + Services |
| `06-app.yml` | Payment Engine app Deployment + NodePort Service |
