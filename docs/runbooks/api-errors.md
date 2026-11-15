# Runbook: API errors (ApiErrorBudgetFastBurn, ApiErrorBudgetSlowBurn)

| Alert | Severity | Fires when |
| --- | --- | --- |
| ApiErrorBudgetFastBurn | page | 5xx ratio above 1.44% over both 5 min and 1 h, for 2 min (2% of the monthly budget in an hour) |
| ApiErrorBudgetSlowBurn | ticket | 5xx ratio above 0.1% over both 6 h and 3 days, for 1 h |

Both measure non-AI requests through api-gateway (`/matches` is excluded; AI has its own alerts in [ai.md](ai.md)).
The SLO is 99.9% of requests without a 5xx.

## What it means
Users are getting server errors on normal pages: board, dashboard, resume, job feed, login. Fast burn means
something broke recently, usually a deploy or a dependency going down. Slow burn means a steady trickle of errors
that will use up the month's budget if left alone.

## First three checks
1. Which service? Prometheus or Grafana Explore:
   ```promql
   sum by (service) (rate(http_server_requests_seconds_count{status=~"5.."}[5m]))
   / sum by (service) (rate(http_server_requests_seconds_count[5m]))
   ```
   If only api-gateway has errors, the gateway itself, or a service it can't reach, is the problem.
2. What changed? Last deploy of that service (Argo CD history), config or secret changes, database migrations.
3. Why is it failing? Loki: `{job="hiretrack", service="<service>", level="ERROR"}`. Open the `traceId` link on an
   error line to see the failing call in Tempo: is it our code, the database, Kafka, Redis or another service?

## Mitigate
- Started with a deploy: roll back that service to its previous version (Argo CD → History → Rollback).
- A dependency is down (Postgres, Redis, Kafka): restore it first; services recover on their own. If Kafka is
  down, writes still succeed (the outbox queues events), so see [events.md](events.md) once it is back.
- One service is overloaded (5xx along with high latency): scale it out, then follow [latency.md](latency.md).
- `UPSTREAM_ERROR` ("A dependent service failed"): the callee returned a 5xx or an unexpected body. Check the
  callee's logs, not the caller's.

## Afterwards
Write a short incident note: start/end time, impact, cause, follow-up. Note how much of the month's error
budget was used.
