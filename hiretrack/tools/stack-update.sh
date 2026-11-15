#!/usr/bin/env bash
# Usage: tools/stack-update.sh match-service
set -euo pipefail
cd "$(dirname "$0")/.."
SVC="$1"
if [ "$SVC" = "frontend" ]; then
  docker compose --env-file .env -f deploy/docker-compose.yml -f deploy/docker-compose.app.yml up -d --build --wait frontend
else
  ./gradlew ":services:$SVC:bootJar" -q
  docker compose --env-file .env -f deploy/docker-compose.yml -f deploy/docker-compose.app.yml up -d --build --wait "$SVC"
fi