#!/usr/bin/env bash
set -euo pipefail
GW=localhost:8080/api/v1
EMAIL="demo$(date +%s)@example.com"
JAR=$(mktemp)

echo "1. signup"
curl -sf -X POST $GW/auth/signup -H 'Content-Type: application/json' -c "$JAR" \
  -d "{\"name\":\"Demo\",\"email\":\"$EMAIL\",\"password\":\"correct-horse\"}" | jq -c '{user}'

echo "2. refresh with the cookie (rotates it)"
TOKEN=$(curl -sf -X POST $GW/auth/refresh -b "$JAR" -c "$JAR" | jq -r .accessToken)

echo "3. me"
curl -sf $GW/auth/me -H "Authorization: Bearer $TOKEN" | jq -c .

echo "4. logout, then refresh must fail"
curl -sf -X POST $GW/auth/logout -b "$JAR" -o /dev/null -w '%{http_code}\n'
curl -s -X POST $GW/auth/refresh -b "$JAR" | jq -c .error