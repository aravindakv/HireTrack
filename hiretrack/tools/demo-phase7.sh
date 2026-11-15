#!/usr/bin/env bash
set -euo pipefail
TOKEN=$(tools/mint-jwt.sh 950)
H=(-H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json')
ID=$(curl -s -X POST localhost:8085/applications "${H[@]}" -H "Idempotency-Key: $(uuidgen)" \
  -d '{"company":"Hooli","role":"SRE"}' | jq .id)
ETAG=$(curl -s -D - -o /dev/null localhost:8085/applications/$ID "${H[@]}" | awk 'tolower($1)=="etag:"{print $2}' | tr -d '\r')
curl -s -o /dev/null -X PATCH localhost:8085/applications/$ID "${H[@]}" -H "If-Match: $ETAG" -d '{"stage":"APPLIED"}'
START=$(date +%s%N)
until [ "$(curl -s "localhost:8087/dashboard?tz=UTC" "${H[@]}" | jq '.byStage.APPLIED')" = "1" ]; do sleep 0.2; done
echo "dashboard updated in $(( ($(date +%s%N) - START) / 1000000 )) ms"
curl -s "localhost:8087/dashboard?tz=UTC" "${H[@]}" | jq -c .