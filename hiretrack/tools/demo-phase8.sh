#!/usr/bin/env bash
# Usage: tools/demo-phase8.sh [userId]
# Needs job-feed-service started with AI_SERVICE_URL=http://localhost:8083 (real ai-service) to get scores;
# the WireMock feed-batch stub returns no scores, so every posting ends up FILTERED.
set -euo pipefail
# A fresh user per run by default: manual refresh is limited to one run per user per hour,
# and postings a user has already seen are never scored again.
U="${1:-$(( $(date +%s) % 100000 + 100000 ))}"
TOKEN=$(tools/mint-jwt.sh "$U"); [ -n "$TOKEN" ] || { echo "could not mint a token" >&2; exit 1; }
H=(-H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json')
psql_feed() { docker compose -f deploy/docker-compose.yml exec -T postgres psql "postgresql://feed_svc:feed_pw@localhost/hiretrack" -tAc "$1"; }
call() {  # method url [curl args...]: fails loudly on a non-2xx response
  local out code; out=$(curl -s -w '\n%{http_code}' -X "$1" "$2" "${@:3}"); code=${out##*$'\n'}
  [[ $code == 2* ]] || { echo "$1 $2 -> HTTP $code: ${out%$'\n'*}" >&2; exit 1; }
}

echo "user $U"
call POST localhost:8082/resumes "${H[@]}" -H "Idempotency-Key: $(uuidgen)" \
  -d '{"text":"Backend engineer: Java 25, Spring Boot, Kafka, PostgreSQL, Docker, AWS. 6 years."}'
call PUT localhost:8086/search-profile "${H[@]}" \
  -d '{"keywords":"java developer","country":"in","location":"Bangalore","radiusKm":25,"remoteOk":true,"enabled":true,"timezone":"Asia/Kolkata"}'
call POST localhost:8086/jobs/refresh "${H[@]}" -H "Idempotency-Key: $(uuidgen)"

echo "waiting for the run (submitter every 2 min, provider batch can take minutes)..."
start=$SECONDS; last=""
while :; do
  status=$(psql_feed "select status from feed_runs where user_id=$U order by created_at desc limit 1")
  [ "$status" != "$last" ] && { printf '  %4ss  %s\n' $((SECONDS - start)) "${status:-<no run yet>}"; last=$status; }
  case "$status" in
    DONE) break ;;
    NOTHING_NEW) echo "run ended NOTHING_NEW: this user has already seen every posting"; exit 1 ;;
    FAILED) echo "run FAILED: $(psql_feed "select error from feed_runs where user_id=$U order by created_at desc limit 1")"; exit 1 ;;
  esac
  sleep 15
done

jobs=$(curl -s localhost:8086/jobs "${H[@]}")
if [ "$(jq '.items | length' <<<"$jobs")" -eq 0 ]; then
  echo "no jobs in feed: every posting was FILTERED (is job-feed-service using the WireMock AI stub?)"
  psql_feed "select status, prefilter_score, posting_id from feed_items where user_id=$U"
  exit 1
fi
jq -r '.items[:5][] | "\(.quickScore)\t\(.title) — \(.company)"' <<<"$jobs"
