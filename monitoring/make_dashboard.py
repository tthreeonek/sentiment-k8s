# Генерирует JSON дашборда Grafana "Sentiment API — Kubernetes" (monitoring/sentiment-dashboard.json)
import json

DS = {"type": "prometheus", "uid": "prometheus"}
NS = 'namespace="ai-app"'
panels = []


def ts(title, exprs, x, y, w=12, h=8, unit="short", legend=None):
    targets = [{"datasource": DS, "expr": e, "legendFormat": l, "refId": chr(65 + i)}
               for i, (e, l) in enumerate(exprs)]
    panels.append({
        "type": "timeseries", "title": title, "datasource": DS, "targets": targets,
        "gridPos": {"x": x, "y": y, "w": w, "h": h},
        "fieldConfig": {"defaults": {"unit": unit, "custom": {"lineWidth": 2, "fillOpacity": 10}},
                        "overrides": []},
        "options": {"legend": {"displayMode": "list", "placement": "bottom"},
                    "tooltip": {"mode": "multi"}},
    })


def stat(title, expr, x, y, w=6, h=4, unit="short", color="green"):
    panels.append({
        "type": "stat", "title": title, "datasource": DS,
        "targets": [{"datasource": DS, "expr": expr, "refId": "A", "range": True}],
        "gridPos": {"x": x, "y": y, "w": w, "h": h},
        "fieldConfig": {"defaults": {"unit": unit, "decimals": 1 if unit != "short" else 0,
                                     "color": {"mode": "fixed", "fixedColor": color}}, "overrides": []},
        "options": {"colorMode": "background", "graphMode": "area", "reduceOptions": {"calcs": ["max"]}},
    })


SVC = f'{NS}, app="sentiment-api"'
URI = f'{SVC}, uri="/api/sentiment"'

stat("Поды приложения (UP), максимум", f'sum(up{{{SVC}}})', 0, 0)
stat("Средний CPU подов, максимум (цель HPA 50%)", f'avg(process_cpu_usage{{{SVC}}} * system_cpu_count{{{SVC}}}) / 0.1', 6, 0, unit="percentunit", color="blue")
stat("Запросов в секунду, максимум", f'sum(rate(http_server_requests_seconds_count{{{URI}}}[1m]))', 12, 0, unit="reqps", color="purple")
stat("Задержка p95, максимум", f'histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket{{{URI}}}[1m])))', 18, 0, unit="s", color="orange")

ts("Запросы /api/sentiment по подам (балансировка)",
   [(f'sum by (pod) (rate(http_server_requests_seconds_count{{{URI}}}[1m]))', "{{pod}}")], 0, 4, unit="reqps")
ts("Предсказания модели по классам",
   [(f'sum by (sentiment) (rate(sentiment_predictions_total{{{SVC}}}[1m]))', "{{sentiment}}")], 12, 4, unit="reqps")

ts("CPU подов, доля от requests = 100m (process_cpu_usage)",
   [(f'sum by (pod) (process_cpu_usage{{{SVC}}} * system_cpu_count{{{SVC}}}) / 0.1', "{{pod}}")],
   0, 12, unit="percentunit")
ts("Число реплик (поды в состоянии UP)",
   [(f'sum(up{{{SVC}}})', "реплики"), ("vector(4)", "maxReplicas HPA"), ("vector(3)", "minReplicas HPA")],
   12, 12)

ts("Задержка HTTP (p50 / p95 / p99)",
   [(f'histogram_quantile({q}, sum by (le) (rate(http_server_requests_seconds_bucket{{{URI}}}[1m])))', f"p{int(q*100)}")
    for q in (0.5, 0.95, 0.99)], 0, 20, unit="s")
ts("JVM heap (used) по подам",
   [(f'sum by (pod) (jvm_memory_used_bytes{{{SVC}, area="heap"}})', "{{pod}}")], 12, 20, unit="bytes")

dash = {
    "uid": "sentiment-api", "title": "Sentiment API — Kubernetes", "tags": ["ai-app", "spring-boot"],
    "timezone": "browser", "schemaVersion": 39, "refresh": "30s",
    "time": {"from": "now-30m", "to": "now"}, "panels": panels,
}
for i, p in enumerate(panels, 1):
    p["id"] = i
with open("sentiment-dashboard.json", "w", encoding="utf-8") as f:
    json.dump(dash, f, ensure_ascii=False, indent=2)
print("panels:", len(panels))
