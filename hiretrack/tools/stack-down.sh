#!/usr/bin/env bash
# Stops everything but keeps data (database, Kafka topics, files).
set -euo pipefail
cd "$(dirname "$0")/.."
docker compose -f deploy/observability/docker-compose.observability.yml -f deploy/observability/docker-compose.observability-stack.yml down
docker compose --env-file .env -f deploy/docker-compose.yml -f deploy/docker-compose.app.yml down