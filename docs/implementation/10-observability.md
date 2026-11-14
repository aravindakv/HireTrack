# Phase 10 — Observability, SLOs and Alerts

**Goal:** see whether HireTrack meets its SLOs (spec 10.2), what the AI costs, and where a slow request spent its time:

- **Metrics:** Micrometer → Prometheus, plus SLO recording rules and burn-rate alerts → Alertmanager
- **Traces:** OpenTelemetry → Tempo; one trace follows a request through gateway, services, Kafka and the AI provider
- **Logs:** JSON (ECS) → Promtail → Loki, linked to traces by `traceId`
- **Dashboards:** Grafana, provisioned from files
- **Runbooks:** one per paging alert

**Time:** 2 days.

---

## Step 10.1 — Turn on trace propagation through Kafka

Add to the service template (`application.yml` of every service that uses Kafka):

```yaml
spring:
  kafka:
    template:
      observation-enabled: true      # producer adds trace headers
    listener:
      observation-enabled: true      # consumer continues the trace
```

The gateway, `RestClient` calls (through `InternalRestClients`, which starts from Boot's builder) and JDBC are already traced by Spring Boot's auto-configuration.

## Step 10.2 — Monitoring containers

`deploy/observability/docker-compose.observability.yml`:

```yaml
name: hiretrack

services:
  prometheus:
    image: prom/prometheus:v2.55.1
    command:
      - --config.file=/etc/prometheus/prometheus.yml
      - --storage.tsdb.retention.time=15d
      - --web.enable-lifecycle
    volumes:
      - ./prometheus.yml:/etc/prometheus/prometheus.yml:ro
      - ./rules:/etc/prometheus/rules:ro
    ports: ["9090:9090"]
    extra_hosts: ["host.docker.internal:host-gateway"]   # services run on the host in Phases 2-11

  alertmanager:
    image: prom/alertmanager:v0.27.0
    volumes: ["./alertmanager.yml:/etc/alertmanager/alertmanager.yml:ro"]
    ports: ["9093:9093"]

  tempo:
    image: grafana/tempo:2.6.1
    command: ["-config.file=/etc/tempo.yml"]
    volumes: ["./tempo.yml:/etc/tempo.yml:ro"]
    ports: ["4318:4318", "3200:3200"]                    # OTLP HTTP in, query API

  loki:
    image: grafana/loki:3.2.1
    command: ["-config.file=/etc/loki/local-config.yaml"]
    ports: ["3100:3100"]

  promtail:
    image: grafana/promtail:3.2.1
    command: ["-config.file=/etc/promtail/config.yml"]
    volumes:
      - ./promtail.yml:/etc/promtail/config.yml:ro
      - ../../logs:/var/log/hiretrack:ro

  grafana:
    image: grafana/grafana:11.3.0
    environment:
      GF_AUTH_ANONYMOUS_ENABLED: "true"
      GF_AUTH_ANONYMOUS_ORG_ROLE: Admin
    volumes:
      - ./grafana/provisioning:/etc/grafana/provisioning:ro
      - ./grafana/dashboards:/var/lib/grafana/dashboards:ro
    ports: ["3000:3000"]
```

`deploy/observability/prometheus.yml`:

```yaml
global:
  scrape_interval: 15s
  evaluation_interval: 15s

rule_files: [/etc/prometheus/rules/*.yml]

alerting:
  alertmanagers:
    - static_configs: [{ targets: ["alertmanager:9093"] }]

scrape_configs:
  - job_name: hiretrack
    metrics_path: /actuator/prometheus
    static_configs:
      - targets:
          - host.docker.internal:8080
          - host.docker.internal:8081
          - host.docker.internal:8082
          - host.docker.internal:8083
          - host.docker.internal:8084
          - host.docker.internal:8085
          - host.docker.internal:8086
          - host.docker.internal:8087
```

The `service` label on every series comes from `management.metrics.tags.service` in each service.

`deploy/observability/tempo.yml`:

```yaml
server:
  http_listen_port: 3200
distributor:
  receivers:
    otlp:
      protocols:
        http:
          endpoint: 0.0.0.0:4318
storage:
  trace:
    backend: local
    local:
      path: /tmp/tempo/traces
    wal:
      path: /tmp/tempo/wal
```

`deploy/observability/promtail.yml`:

```yaml
server:
  http_listen_port: 9080
positions:
  filename: /tmp/positions.yaml
clients:
  - url: http://loki:3100/loki/api/v1/push
scrape_configs:
  - job_name: hiretrack
    static_configs:
      - targets: [localhost]
        labels:
          job: hiretrack
          __path__: /var/log/hiretrack/*.log
    pipeline_stages:
      - regex:
          source: filename
          expression: '/var/log/hiretrack/(?P<service>[a-z-]+)\.log'
      - labels:
          service:
      - json:
          expressions:
            level: log.level
            trace_id: trace.id
      - labels:
          level:
```

`deploy/observability/alertmanager.yml`:

```yaml
route:
  receiver: default
  group_by: [alertname, service]
  routes:
    - matchers: [severity="page"]
      receiver: pager
receivers:
  - name: default
  - name: pager
    # Replace with your Slack/PagerDuty/email receiver. Locally, alerts are visible at http://localhost:9093
    webhook_configs:
      - url: http://host.docker.internal:9999/alerts
        send_resolved: true
```

## Step 10.3 — SLO recording rules and alerts

`deploy/observability/rules/hiretrack-slo.yml`:

```yaml
groups:
  - name: slo-recording
    rules:
      # Availability: non-AI gateway requests, 5xx = bad
      - record: hiretrack:api_error_ratio:rate5m
        expr: |
          sum(rate(http_server_requests_seconds_count{service="api-gateway",uri!~".*matches.*",status=~"5.."}[5m]))
          / sum(rate(http_server_requests_seconds_count{service="api-gateway",uri!~".*matches.*"}[5m]))
      - record: hiretrack:api_error_ratio:rate1h
        expr: |
          sum(rate(http_server_requests_seconds_count{service="api-gateway",uri!~".*matches.*",status=~"5.."}[1h]))
          / sum(rate(http_server_requests_seconds_count{service="api-gateway",uri!~".*matches.*"}[1h]))
      - record: hiretrack:api_error_ratio:rate6h
        expr: |
          sum(rate(http_server_requests_seconds_count{service="api-gateway",uri!~".*matches.*",status=~"5.."}[6h]))
          / sum(rate(http_server_requests_seconds_count{service="api-gateway",uri!~".*matches.*"}[6h]))
      - record: hiretrack:api_error_ratio:rate3d
        expr: |
          sum(rate(http_server_requests_seconds_count{service="api-gateway",uri!~".*matches.*",status=~"5.."}[3d]))
          / sum(rate(http_server_requests_seconds_count{service="api-gateway",uri!~".*matches.*"}[3d]))
      # Read latency p95 per service
      - record: hiretrack:read_latency_p95:5m
        expr: |
          histogram_quantile(0.95, sum by (service, le) (
            rate(http_server_requests_seconds_bucket{method="GET",uri!~"/actuator.*"}[5m])))
      # AI match latency p95 and success ratio
      - record: hiretrack:ai_match_latency_p95:10m
        expr: histogram_quantile(0.95, sum by (le) (rate(hiretrack_ai_latency_seconds_bucket{operation="MATCH"}[10m])))
      - record: hiretrack:ai_match_success_ratio:1h
        expr: |
          sum(rate(hiretrack_ai_requests_total{operation="MATCH",outcome="ok"}[1h]))
          / sum(rate(hiretrack_ai_requests_total{operation="MATCH",outcome!~"busy"}[1h]))
      # Spend
      - record: hiretrack:ai_cost_usd:24h
        expr: sum(increase(hiretrack_ai_cost_usd_total[24h]))

  - name: slo-alerts
    rules:
      # 99.9% → error budget 0.001. Fast burn: 2% of the monthly budget in 1 hour (14.4×).
      - alert: ApiErrorBudgetFastBurn
        expr: hiretrack:api_error_ratio:rate5m > (14.4 * 0.001) and hiretrack:api_error_ratio:rate1h > (14.4 * 0.001)
        for: 2m
        labels: { severity: page }
        annotations:
          summary: API is burning its error budget fast
          runbook: docs/runbooks/api-errors.md
      # Slow burn: 10% of the budget in 3 days (1×), checked over 6h and 3d.
      - alert: ApiErrorBudgetSlowBurn
        expr: hiretrack:api_error_ratio:rate6h > 0.001 and hiretrack:api_error_ratio:rate3d > 0.001
        for: 1h
        labels: { severity: ticket }
        annotations:
          summary: API error rate is above the SLO
          runbook: docs/runbooks/api-errors.md
      - alert: ReadLatencyHigh
        expr: hiretrack:read_latency_p95:5m > 0.3
        for: 10m
        labels: { severity: ticket }
        annotations:
          summary: "p95 read latency above 300 ms on {{ $labels.service }}"
          runbook: docs/runbooks/latency.md
      - alert: AiMatchLatencyHigh
        expr: hiretrack:ai_match_latency_p95:10m > 15
        for: 10m
        labels: { severity: page }
        annotations:
          summary: AI match p95 above 15 s
          runbook: docs/runbooks/ai.md
      - alert: AiMatchSuccessLow
        expr: hiretrack:ai_match_success_ratio:1h < 0.99
        for: 15m
        labels: { severity: page }
        annotations:
          summary: AI match success below 99%
          runbook: docs/runbooks/ai.md
      - alert: AiSpendOverBudget
        expr: hiretrack:ai_cost_usd:24h > 1.2 * 50        # replace 50 with your daily AI budget in USD
        labels: { severity: page }
        annotations:
          summary: AI spend in the last 24 h is above 120% of budget
          runbook: docs/runbooks/ai.md
      - alert: OutboxStuck
        expr: max by (service) (hiretrack_outbox_oldest_unpublished_seconds) > 60
        for: 2m
        labels: { severity: page }
        annotations:
          summary: "{{ $labels.service }} can't publish events to Kafka"
          runbook: docs/runbooks/events.md
      - alert: ConsumerLagHigh
        expr: sum by (service) (kafka_consumer_fetch_manager_records_lag_max) > 1000
        for: 10m
        labels: { severity: ticket }
        annotations:
          summary: "{{ $labels.service }} is falling behind on Kafka"
          runbook: docs/runbooks/events.md
      - alert: DbPoolSaturated
        expr: max by (service) (hikaricp_connections_pending) > 0
        for: 5m
        labels: { severity: ticket }
        annotations:
          summary: "{{ $labels.service }} is waiting for database connections"
          runbook: docs/runbooks/latency.md
      - alert: FeedRunsFailing
        expr: |
          sum(increase(hiretrack_feed_runs_total{status="FAILED"}[1h]))
          / clamp_min(sum(increase(hiretrack_feed_runs_total[1h])), 1) > 0.01
        for: 30m
        labels: { severity: ticket }
        annotations:
          summary: More than 1% of feed runs failed in the last hour
          runbook: docs/runbooks/feed.md
```

## Step 10.4 — Grafana provisioning

`deploy/observability/grafana/provisioning/datasources/datasources.yml`:

```yaml
apiVersion: 1
datasources:
  - name: Prometheus
    uid: prometheus
    type: prometheus
    url: http://prometheus:9090
    isDefault: true
  - name: Tempo
    uid: tempo
    type: tempo
    url: http://tempo:3200
    jsonData:
      tracesToLogsV2:
        datasourceUid: loki
        filterByTraceID: true
  - name: Loki
    uid: loki
    type: loki
    url: http://loki:3100
    jsonData:
      derivedFields:
        - name: traceId
          matcherRegex: '"trace.id":"(\w+)"'
          datasourceUid: tempo
          url: '$${__value.raw}'
```

`deploy/observability/grafana/provisioning/dashboards/dashboards.yml`:

```yaml
apiVersion: 1
providers:
  - name: hiretrack
    folder: HireTrack
    type: file
    options:
      path: /var/lib/grafana/dashboards
```

`deploy/observability/grafana/dashboards/ai-cost.json`, the AI & cost dashboard:

```json
{
  "uid": "hiretrack-ai",
  "title": "HireTrack — AI and cost",
  "schemaVersion": 39,
  "time": { "from": "now-24h", "to": "now" },
  "templating": { "list": [] },
  "panels": [
    { "type": "stat", "title": "AI spend, last 24 h (USD)", "gridPos": { "x": 0, "y": 0, "w": 6, "h": 4 },
      "datasource": { "uid": "prometheus" },
      "targets": [{ "expr": "hiretrack:ai_cost_usd:24h" }],
      "fieldConfig": { "defaults": { "unit": "currencyUSD", "decimals": 2 } } },
    { "type": "stat", "title": "Match p95 latency", "gridPos": { "x": 6, "y": 0, "w": 6, "h": 4 },
      "datasource": { "uid": "prometheus" },
      "targets": [{ "expr": "hiretrack:ai_match_latency_p95:10m" }],
      "fieldConfig": { "defaults": { "unit": "s", "thresholds": { "mode": "absolute",
        "steps": [{ "color": "green", "value": null }, { "color": "orange", "value": 12 }, { "color": "red", "value": 15 }] } } } },
    { "type": "stat", "title": "Match success (1 h)", "gridPos": { "x": 12, "y": 0, "w": 6, "h": 4 },
      "datasource": { "uid": "prometheus" },
      "targets": [{ "expr": "hiretrack:ai_match_success_ratio:1h" }],
      "fieldConfig": { "defaults": { "unit": "percentunit", "decimals": 2 } } },
    { "type": "stat", "title": "Cache-read share of input tokens", "gridPos": { "x": 18, "y": 0, "w": 6, "h": 4 },
      "datasource": { "uid": "prometheus" },
      "targets": [{ "expr": "sum(rate(hiretrack_ai_tokens_total{type=\"cache_read\"}[1h])) / sum(rate(hiretrack_ai_tokens_total{type=~\"input|cache_read\"}[1h]))" }],
      "fieldConfig": { "defaults": { "unit": "percentunit" } } },
    { "type": "timeseries", "title": "AI calls by outcome", "gridPos": { "x": 0, "y": 4, "w": 12, "h": 8 },
      "datasource": { "uid": "prometheus" },
      "targets": [{ "expr": "sum by (operation, outcome) (rate(hiretrack_ai_requests_total[5m]))", "legendFormat": "{{operation}} {{outcome}}" }] },
    { "type": "timeseries", "title": "Spend per hour by model and operation (USD)", "gridPos": { "x": 12, "y": 4, "w": 12, "h": 8 },
      "datasource": { "uid": "prometheus" },
      "targets": [{ "expr": "sum by (provider, model, operation) (increase(hiretrack_ai_cost_usd_total[1h]))", "legendFormat": "{{provider}} {{model}} {{operation}}" }],
      "fieldConfig": { "defaults": { "unit": "currencyUSD" } } },
    { "type": "timeseries", "title": "Tokens per minute by type", "gridPos": { "x": 0, "y": 12, "w": 12, "h": 8 },
      "datasource": { "uid": "prometheus" },
      "targets": [{ "expr": "sum by (type) (rate(hiretrack_ai_tokens_total[5m])) * 60", "legendFormat": "{{type}}" }] },
    { "type": "timeseries", "title": "Bulkhead free slots", "gridPos": { "x": 12, "y": 12, "w": 12, "h": 8 },
      "datasource": { "uid": "prometheus" },
      "targets": [{ "expr": "resilience4j_bulkhead_available_concurrent_calls{name=\"ai-match\"}", "legendFormat": "{{instance}}" }] }
  ]
}
```

Build the other dashboards in Grafana's UI from these queries. Export each one with **Share → Export → Save to file** into `deploy/observability/grafana/dashboards/`, so they're provisioned next time.

| Dashboard | Panel | PromQL |
| --- | --- | --- |
| Service overview | Requests/s by service | `sum by (service) (rate(http_server_requests_seconds_count[5m]))` |
| | Error ratio by service | `sum by (service) (rate(http_server_requests_seconds_count{status=~"5.."}[5m])) / sum by (service) (rate(http_server_requests_seconds_count[5m]))` |
| | p95 / p99 latency | `histogram_quantile(0.95, sum by (service, le) (rate(http_server_requests_seconds_bucket[5m])))` |
| | JVM heap used | `sum by (service) (jvm_memory_used_bytes{area="heap"})` |
| | DB connections active / pending | `hikaricp_connections_active`, `hikaricp_connections_pending` |
| Feed | Runs by status | `sum by (status) (increase(hiretrack_feed_runs_total[1h]))` |
| | Posting funnel | `sum by (stage) (increase(hiretrack_feed_postings_total[24h]))` |
| | Adzuna errors and p95 | `sum by (outcome) (rate(hiretrack_adzuna_requests_total[5m]))`, `histogram_quantile(0.95, sum by (le) (rate(hiretrack_adzuna_latency_seconds_bucket[5m])))` |
| | Query cache hit ratio | `sum(rate(hiretrack_feed_query_cache_hits_total{result="hit"}[1h])) / sum(rate(hiretrack_feed_query_cache_hits_total[1h]))` |
| Events | Oldest unpublished outbox row | `max by (service) (hiretrack_outbox_oldest_unpublished_seconds)` |
| | Consumer lag | `sum by (service) (kafka_consumer_fetch_manager_records_lag_max)` |
| | Idempotent replays | `sum by (operation, result) (rate(hiretrack_idempotency_total[5m]))` |
| Business | Matches per day | `sum(increase(hiretrack_ai_requests_total{operation="MATCH",outcome="ok"}[24h]))` |
| | Stage changes per day | `sum by (to) (increase(hiretrack_applications_stage_changes_total[24h]))` |

For the last panel, add this counter to `ApplicationEvents.application(...)` in application-service (Phase 4) when the type is `application.stage-changed`:

```java
meters.counter("hiretrack.applications.stage.changes", "from", fromStage, "to", a.getStage().name()).increment();
```

## Step 10.5 — Runbooks

Create `docs/runbooks/`. Each paging alert links to one. Template, using `docs/runbooks/ai.md` as the example:

```markdown
# Runbook: AI alerts (AiMatchLatencyHigh, AiMatchSuccessLow, AiSpendOverBudget)

## What it means
Matches are slow, failing, or costing more than budget. Users see 502/429 on the Match page, or nothing is wrong
for users but the bill is growing.

## First three checks
1. Grafana → "HireTrack — AI and cost": which outcome is rising (timeout, error, rate_limited, refusal, invalid)?
2. The AI provider's status page (OpenAI by default; Anthropic if `hiretrack.ai.provider=anthropic`).
3. Tempo: open a slow /matches trace; is the time in ai-service → provider, or before it?

## Mitigate
- Provider outage or rate limiting: lower `resilience4j.bulkhead.instances.ai-match.max-concurrent-calls`, or switch
  provider with `AI_PROVIDER=anthropic` (needs that provider's key and models configured) and restart ai-service.
- Spend spike: lower `hiretrack.ai.daily-match-quota`; pause the feed with `FEED_SCHEDULERS_ENABLED=false` on
  job-feed-service; check the top users by cost:
  `SELECT user_id, sum(cost_usd) FROM ai.ai_usage WHERE created_at > now() - interval '1 day' GROUP BY 1 ORDER BY 2 DESC LIMIT 10;`
- Invalid output rising after a model or prompt change: roll back the change; run the eval set (Phase 11).

## Afterwards
Write a short incident note: start/end time, impact, cause, follow-up.
```

Write the other runbooks the same way:

| File | Alerts | First check | Typical mitigation |
| --- | --- | --- | --- |
| `api-errors.md` | ApiErrorBudgetFastBurn / SlowBurn | Error ratio by service: which one? | Roll back the last deploy of that service |
| `latency.md` | ReadLatencyHigh, DbPoolSaturated | Slow traces: DB, downstream or CPU? | Scale pods; add an index; raise the pool size within the DB connection budget |
| `events.md` | OutboxStuck, ConsumerLagHigh | Kafka broker health; service logs for publish errors | Restart the stuck relay or consumer; scale consumers; inspect `<topic>.DLT` |
| `feed.md` | FeedRunsFailing | `feed_runs.error` and Adzuna error rate | Wait out an Adzuna outage (runs retry tomorrow); check quota; check ai-service batches |

## Step 10.6 — Start it and look

```bash
docker compose -f deploy/observability/docker-compose.observability.yml up -d
# restart running services so they pick up the Kafka observation settings
```

**Verify**

1. **Prometheus targets:** `http://localhost:9090/targets` lists the eight `hiretrack` targets as **UP** for the services you're running.
2. **Rules loaded:** `http://localhost:9090/rules` shows `slo-recording` and `slo-alerts`.
3. **One trace end to end:** run a match through the frontend or gateway. In Grafana (`http://localhost:3000`) open Explore → Tempo → Search → service `api-gateway`. Open the newest `POST /api/v1/matches` trace. It contains spans for `api-gateway` → `match-service` → `resume-service` (GET) → `ai-service` → the HTTPS call to the provider. The provider span takes most of the time.
4. **Logs to trace:** Explore → Loki: `{job="hiretrack", service="match-service"}`. Expand a line and click the `traceId` link: it opens the same trace.
5. **AI dashboard:** Dashboards → HireTrack → "AI and cost" shows real token counts and spend after one real match. Spend shows 0 until you set prices.
6. **An alert fires:** stop Kafka (`docker compose -f deploy/docker-compose.yml stop kafka`), then create an application. Within about 3 minutes `OutboxStuck` is **firing** at `http://localhost:9093`. Start Kafka again; the alert resolves, and the event is delivered (check analytics).

## Phase demo

```bash
curl -s localhost:9090/api/v1/query --data-urlencode 'query=up{job="hiretrack"}' | jq -r '.data.result[] | "\(.labels.instance) \(.value[1])"'
curl -s localhost:9090/api/v1/query --data-urlencode 'query=sum(hiretrack_ai_requests_total) by (outcome)' | jq -c '.data.result'
curl -s 'localhost:3200/api/search?tags=service.name%3Dmatch-service&limit=1' | jq '.traces | length'
```

Expected: `1` (up) for each running service, AI request counts by outcome, and at least one match-service trace.

## Done when

- [ ] Metrics, traces and logs from every service reach Prometheus, Tempo and Loki
- [ ] One trace shows the full match path, including the AI provider call
- [ ] The AI & cost dashboard shows real tokens (and dollars once prices are set)
- [ ] Each paging alert has fired in a staged test and links to a runbook
- [ ] Commit: `git commit -am "Phase 10: observability"`
