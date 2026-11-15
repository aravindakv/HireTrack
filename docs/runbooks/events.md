# Runbook: Events (OutboxStuck, ConsumerLagHigh)

| Alert | Severity | Fires when |
| --- | --- | --- |
| OutboxStuck | page | A service's oldest unpublished outbox event is older than 60 s, for 2 min |
| ConsumerLagHigh | ticket | A service's Kafka consumer is more than 1000 records behind, for 10 min |

## What it means
- **OutboxStuck:** a service saves events in its `outbox` table in the same transaction as the change, and a relay
  sends them to Kafka every 500 ms. When the relay can't publish, user writes still succeed, but nothing downstream
  hears about them: the dashboard (analytics) and the job feed stop updating. Nothing is lost; events wait in the table.
- **ConsumerLagHigh:** events are published but a consumer (analytics, job-feed workers) can't keep up, so its
  data is minutes or hours stale.

## First three checks
1. Kafka broker health: is the broker up and reachable from the services?
   ```bash
   docker compose -f deploy/docker-compose.yml exec -T kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list
   ```
2. The alerting service's logs for publish errors: Loki `{job="hiretrack", service="<service>", level=~"WARN|ERROR"}`.
   How big is the backlog? Use the service's own schema (`identity`, `resume`, `application`, `match`, `feed`):
   ```sql
   SELECT topic, count(*), min(created_at) FROM <schema>.outbox WHERE published_at IS NULL GROUP BY 1;
   ```
3. For lag: which group and partition is behind, and is the consumer moving at all?
   ```bash
   docker compose -f deploy/docker-compose.yml exec -T kafka /opt/kafka/bin/kafka-consumer-groups.sh \
     --bootstrap-server localhost:9092 --describe --group <analytics|job-feed-workers|job-feed-activity>
   ```
   If the offset doesn't move between two runs, the consumer is stuck, not slow.

## Mitigate
- Kafka down: bring the broker back. Relays and consumers reconnect on their own, and the outbox drains in order.
- Relay stuck while Kafka is healthy: restart that service (the relay runs inside it; several pods are safe,
  thanks to `FOR UPDATE SKIP LOCKED`).
- Consumer slow: scale out the consuming service, up to the topic's partition count.
- Consumer stuck on a bad record: after 3 retries the record goes to `<topic>.DLT` and the partition moves on.
  Inspect what landed there:
  ```bash
  docker compose -f deploy/docker-compose.yml exec -T kafka /opt/kafka/bin/kafka-console-consumer.sh \
    --bootstrap-server localhost:9092 --topic <topic>.DLT --from-beginning --property print.headers=true --timeout-ms 5000
  ```
  Fix the producer or consumer, then republish the records to the main topic if they still matter.

## Afterwards
Write a short incident note: start/end time, impact (how long downstream data was stale), cause, follow-up.
Check that the backlog reached zero and that the DLT holds nothing unexplained.
