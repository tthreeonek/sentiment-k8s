# Sentiment API в Kubernetes (Minikube)

Итоговый проект: контейнерное Java-приложение с ИИ-моделью анализа тональности текста,
развёрнутое в Minikube с балансировкой нагрузки, автомасштабированием и мониторингом
(Prometheus + Grafana).

## Стек

| Компонент | Версия / технология |
|---|---|
| Приложение | Java 21, Spring Boot 3.5 (Web + Actuator), Micrometer Prometheus |
| ИИ-модель | мультиномиальный наивный байесовский классификатор (positive / negative / neutral), обучается при старте на `train.tsv` (88 фраз, en + ru) |
| Образ | multi-stage Dockerfile, JRE собрана через `jlink`, база `alpine:3.22` — **66,4 МБ** (сжатый), ≈97 МБ распакованный |
| Кластер | Minikube v1.39.0 (driver docker), Kubernetes v1.37.0, Helm v3.22.1 |
| Kubernetes-объекты | Deployment (3 реплики), Service `LoadBalancer`, Ingress (`/api`), HPA (CPU 50 %, 3–4 пода) |
| Мониторинг | Helm-чарты `prometheus-community/prometheus` и `grafana/grafana`, сбор метрик Actuator по аннотациям подов |

## Структура

```
app/                      исходный код и Dockerfile
  src/main/java/ru/hw/sentiment/
    SentimentApplication.java
    SentimentController.java   REST: /api/sentiment, /api/info
    NaiveBayesModel.java       модель тональности
  src/main/resources/
    application.yml            Actuator: health (probes), prometheus
    train.tsv                  обучающая выборка
k8s/                      манифесты Kubernetes
  00-namespace.yaml  01-deployment.yaml  02-service.yaml
  03-ingress.yaml    04-hpa.yaml
loadtest/
  load-generator.yaml        нагрузка для проверки HPA
scripts/
  lower-requests.sh          настройка узла 1 vCPU / 2 ГБ (CPU requests, пробы control plane)
monitoring/
  prometheus-values.yaml     values Helm для Prometheus (облегчённая установка)
  grafana-values.yaml        values Helm для Grafana (источник данных + дашборд через provisioning)
  sentiment-dashboard.json   дашборд Grafana
  grafana-dashboard-cm.yaml  тот же дашборд как ConfigMap (монтируется в Grafana)
  make_dashboard.py          генератор JSON дашборда
```

## API

| Метод | URL | Ответ |
|---|---|---|
| GET | `/api/sentiment?text=hello` | `{"text":"hello","sentiment":"positive","confidence":…,"probabilities":{…},"pod":"…"}` |
| POST | `/api/sentiment` с телом `{"text":"…"}` | то же |
| GET | `/api/info` | сведения о модели и поде |
| GET | `/actuator/health/liveness`, `/readiness` | пробы Kubernetes |
| GET | `/actuator/prometheus` | метрики для Prometheus |

Собственные метрики: `sentiment_predictions_total{sentiment=…}` и гистограмма `sentiment_inference_seconds`.

Пример:

```
$ curl -s "http://$(minikube ip)/api/sentiment?text=hello"
{"text":"hello","sentiment":"positive","confidence":0.638,
 "probabilities":{"positive":0.638,"negative":0.197,"neutral":0.166},"pod":"sentiment-api-56dd9fd688-7dhv6"}
```

## Запуск

```bash
# 1. Кластер (на машине с 4+ CPU можно как в плане: --cpus=4 --memory=8192mb --nodes=2)
minikube start --driver=docker --cpus=1 --memory=no-limit --addons=metrics-server,ingress --force
./scripts/lower-requests.sh            # только для узла с 1 vCPU / 2 ГБ

# 2. Образ
cd app && docker build -t sentiment-api:1.0 . && cd ..
minikube image load sentiment-api:1.0

# 3. Мониторинг
helm repo add prometheus-community https://prometheus-community.github.io/helm-charts
helm repo add grafana https://grafana.github.io/helm-charts
helm repo update
kubectl create namespace monitoring
kubectl apply -f monitoring/grafana-dashboard-cm.yaml
helm install prometheus prometheus-community/prometheus -n monitoring -f monitoring/prometheus-values.yaml
helm install grafana grafana/grafana -n monitoring -f monitoring/grafana-values.yaml

# 4. Приложение
kubectl apply -f k8s/

# 5. Внешний IP для Service LoadBalancer (в отдельном терминале)
minikube tunnel
```

Проверка:

```bash
kubectl -n ai-app get deploy,pods,svc,ingress,hpa
curl "http://$(minikube ip)/api/sentiment?text=hello"                 # через Ingress
curl "http://<EXTERNAL-IP>/api/sentiment?text=hello"                  # через LoadBalancer
```

Нагрузочный тест HPA:

```bash
kubectl apply -f loadtest/load-generator.yaml
kubectl -n ai-app get hpa sentiment-api -w
kubectl delete -f loadtest/load-generator.yaml
```

Prometheus: `kubectl -n monitoring port-forward svc/prometheus-server 9090:80` → http://localhost:9090/targets
Grafana: `kubectl -n monitoring port-forward svc/grafana 3000:80` → http://localhost:3000,
дашборд **Sentiment API — Kubernetes**.

## Замечание о ресурсах

Стенд — VPS с 1 vCPU и 2 ГБ RAM (+4 ГБ swap), поэтому:

* кластер одноузловой, а CPU requests системных компонентов уменьшены скриптом `scripts/lower-requests.sh`
  (иначе поды остаются в `Pending` с причиной `Insufficient cpu`);
* вместо `kube-prometheus-stack` используются отдельные чарты Prometheus и Grafana: CRD и Prometheus Operator
  вместе с полным стеком не помещались в 2 ГБ (kube-apiserver один занимал ~470 МБ, узел уходил в swap);
* HPA ограничен 4 репликами, JVM запускается с `-Xmx64m`.

На машине с 4 CPU / 8 ГБ эти ограничения не нужны.
