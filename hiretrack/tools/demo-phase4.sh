#!/usr/bin/env bash
set -euo pipefail
SVC=localhost:8085
TOKEN=$(tools/mint-jwt.sh 600)
H=(-H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json')

ID=$(curl -s -X POST $SVC/applications "${H[@]}" -H "Idempotency-Key: $(uuidgen)" \
  -d '{"company":"Initech","role":"Platform Engineer"}' | jq .id)
for STAGE in APPLIED INTERVIEW OFFER WISHLIST; do
  ETAG=$(curl -s -D - -o /dev/null $SVC/applications/$ID "${H[@]}" | awk 'tolower($1)=="etag:"{print $2}' | tr -d '\r')
  curl -s -X PATCH $SVC/applications/$ID "${H[@]}" -H "If-Match: $ETAG" -d "{\"stage\":\"$STAGE\"}" \
    | jq -c '{stage, applied: (.appliedDate != null), replied: (.respondedAt != null)}'
done