#!/usr/bin/env bash
set -euo pipefail
SVC=localhost:8082
TOKEN=$(tools/mint-jwt.sh 500)
KEY=$(uuidgen)

echo "1. paste (twice with the same key → one resume)"
curl -s -X POST $SVC/resumes -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -H "Idempotency-Key: $KEY" \
  -d '{"text":"Senior Java engineer: Spring Boot, Kafka, PostgreSQL, AWS"}' | jq -c '{id, active}'
curl -s -D - -o /dev/null -X POST $SVC/resumes -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $KEY" -d '{"text":"Senior Java engineer: Spring Boot, Kafka, PostgreSQL, AWS"}' | grep -i idempotent-replayed

echo "2. active resume via the internal API"
curl -s $SVC/internal/users/500/active-resume -H 'X-Internal-Token: dev-internal-token' | jq -r .content

echo "3. resume.activated event"
docker compose -f deploy/docker-compose.yml exec -T kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 --topic resume-events --from-beginning --timeout-ms 4000 2>/dev/null \
  | jq -c 'select(.userId == 500) | {type, payload}'