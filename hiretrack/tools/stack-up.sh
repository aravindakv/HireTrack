#!/usr/bin/env bash
# Builds and starts the whole system. Usage: tools/stack-up.sh [--no-observability]
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

[ -f .env ] || { echo "Missing .env: copy .env.example to .env and set OPENAI_API_KEY and OPENAI_MODEL."; exit 1; }
grep -q '^OPENAI_API_KEY=sk-' .env || echo "Warning: OPENAI_API_KEY looks unset; matches will fail."
[ -f deploy/dev-keys/private.pem ] || tools/gen-dev-keys.sh
mkdir -p logs

echo "Building service jars..."
./gradlew bootJar -q

echo "Starting infrastructure and services (first run takes a few minutes)..."
docker compose --env-file .env -f deploy/docker-compose.yml -f deploy/docker-compose.app.yml up -d --build --wait

if [ "${1:-}" != "--no-observability" ]; then
  docker compose -f deploy/observability/docker-compose.observability.yml \
                 -f deploy/observability/docker-compose.observability-stack.yml up -d
fi

cat <<EOF

HireTrack is running.
  App:          http://localhost
  API gateway:  http://localhost:8080
  Grafana:      http://localhost:3000
  Prometheus:   http://localhost:9090
  Alerts:       http://localhost:9093
  S3 (S3Mock):  http://localhost:9000  (path-style, no login, no console)
EOF