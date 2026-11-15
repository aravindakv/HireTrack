#!/usr/bin/env bash
# Stops everything AND deletes all local data. Asks first.
set -euo pipefail
cd "$(dirname "$0")/.."
read -r -p "Delete all local HireTrack data (users, resumes, applications)? Type 'yes': " ok
[ "$ok" = "yes" ] || { echo "Cancelled."; exit 0; }
docker compose -f deploy/observability/docker-compose.observability.yml -f deploy/observability/docker-compose.observability-stack.yml down -v
docker compose --env-file .env -f deploy/docker-compose.yml -f deploy/docker-compose.app.yml down -v
rm -rf logs/*