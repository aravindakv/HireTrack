#!/usr/bin/env bash
set -euo pipefail
SVC=localhost:8083
H=(-H 'X-Internal-Token: dev-internal-token' -H 'Content-Type: application/json')
RID="demo-$(date +%s)"
BODY='{"userId":700,"requestId":"'$RID'","resumeText":"Python, Django, PostgreSQL, Docker; 3 years.","jobDescription":"Globex seeks a Backend Developer: Python, FastAPI, PostgreSQL, Kubernetes."}'

echo "1. first call (real AI)"
/usr/bin/time -f "%es" curl -s -X POST $SVC/internal/match "${H[@]}" -d "$BODY" | jq -c '{fitScore, company, role, provider}'
echo "2. retry with the same requestId (stored, no charge)"
/usr/bin/time -f "%es" curl -s -X POST $SVC/internal/match "${H[@]}" -d "$BODY" | jq -c '{fitScore}'
echo "3. ledger"
docker compose -f deploy/docker-compose.yml exec -T postgres psql "postgresql://ai_svc:ai_pw@localhost/hiretrack" -tAc \
  "select provider, model, input_tokens, output_tokens, cost_usd from ai_usage where usage_key = '$RID'"