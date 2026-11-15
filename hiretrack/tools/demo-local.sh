#!/usr/bin/env bash
set -euo pipefail
BASE=http://localhost/api/v1
JAR=$(mktemp); EMAIL="demo$(date +%s)@example.com"
J=(-H 'Content-Type: application/json')

TOKEN=$(curl -sf -X POST $BASE/auth/signup "${J[@]}" -c "$JAR" \
  -d "{\"name\":\"Demo\",\"email\":\"$EMAIL\",\"password\":\"correct-horse\"}" | jq -r .accessToken)
A=(-H "Authorization: Bearer $TOKEN")

curl -sf -X POST $BASE/resumes "${A[@]}" "${J[@]}" -H "Idempotency-Key: $(uuidgen)" \
  -d '{"text":"Software engineer. Java, Spring Boot, Kafka, PostgreSQL, Docker. 5 years building payment APIs."}' >/dev/null
echo "resume saved"

MATCH=$(curl -sf -X POST $BASE/matches "${A[@]}" "${J[@]}" -H "Idempotency-Key: $(uuidgen)" --max-time 20 \
  -d '{"jobDescription":"Fintech company Contoso is hiring a Backend Engineer: Java, Kafka, Kubernetes, payments experience."}')
echo "$MATCH" | jq -c '{fitScore, company, role, missingSkills}'
MATCH_ID=$(echo "$MATCH" | jq .id)

APP=$(curl -sf -X POST $BASE/applications "${A[@]}" "${J[@]}" -H "Idempotency-Key: $(uuidgen)" \
  -d "{\"company\":\"Contoso\",\"role\":\"Backend Engineer\",\"source\":{\"type\":\"MATCH\",\"id\":\"$MATCH_ID\"}}")
APP_ID=$(echo "$APP" | jq .id)
curl -sf -X PATCH $BASE/applications/$APP_ID "${A[@]}" "${J[@]}" -H "If-Match: \"$(echo "$APP" | jq .version)\"" \
  -d '{"stage":"APPLIED"}' | jq -c '{stage, appliedDate}'

sleep 3
curl -sf "$BASE/dashboard?tz=$(cat /etc/timezone 2>/dev/null || echo UTC)" "${A[@]}" | jq -c '{byStage, sent, responseRate}'
echo "open http://localhost and log in as $EMAIL / correct-horse"