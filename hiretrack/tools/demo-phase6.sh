#!/usr/bin/env bash
set -euo pipefail
TOKEN=$(tools/mint-jwt.sh 800)
curl -s -X POST localhost:8082/resumes -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $(uuidgen)" -d '{"text":"Frontend developer. React, TypeScript, CSS, Jest. 4 years at a fintech startup."}' >/dev/null
curl -s -X POST localhost:8084/matches -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $(uuidgen)" \
  -d '{"jobDescription":"Initrode is hiring a Senior Frontend Engineer: React, TypeScript, GraphQL, accessibility, design systems."}' \
  | jq '{fitScore, missingSkills, company, role, suggestedBullets}'