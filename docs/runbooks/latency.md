# Runbook: Latency (ReadLatencyHigh, DbPoolSaturated)

| Alert | Severity | Fires when |
| --- | --- | --- |
| ReadLatencyHigh | ticket | p95 of GET requests on a service above 300 ms for 10 min |
| DbPoolSaturated | ticket | A service has requests waiting for a database connection for 5 min |

## What it means
Pages load slowly. Requests on one service spend their time waiting: on the database, on another service, or on
CPU. DbPoolSaturated means every connection in that service's Hikari pool is in use and new requests are queuing,
which shows up as latency first and as timeouts (5xx) if it gets worse.

## First three checks
1. Which service and which endpoints? Grafana Explore:
   ```promql
   histogram_quantile(0.95, sum by (uri, le) (rate(http_server_requests_seconds_bucket{service="<service>",method="GET"}[5m])))
   ```
2. Tempo: open slow traces for that endpoint. Is the time in a database span, a call to another service, or
   inside our own code (CPU, garbage collection)?
3. Database: pool usage (`hikaricp_connections_active`, `hikaricp_connections_pending` by service) and slow queries:
   ```sql
   SELECT now() - query_start AS running, state, left(query, 120) FROM pg_stat_activity
   WHERE datname = 'hiretrack' AND state <> 'idle' ORDER BY 1 DESC LIMIT 10;
   ```

## Mitigate
- CPU-bound or simply busy: scale out the service (more pods).
- A slow query: add the missing index in a new Flyway migration; meanwhile cancel runaway queries with
  `SELECT pg_cancel_backend(<pid>);`.
- Pool too small: raise `DB_POOL_SIZE` (default 10) for that service, but stay within the database connection budget:
  pool size × pods summed over all services must stay below Postgres `max_connections` (default 100), with room
  left for migrations and admin sessions.
- Slow downstream service: follow that service's latency instead; the caller is only waiting.

## Afterwards
Write a short incident note if users were affected: start/end time, impact, cause, follow-up. Add the index or
capacity change to the backlog if it was a temporary fix.
