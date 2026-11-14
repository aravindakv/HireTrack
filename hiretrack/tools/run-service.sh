#!/usr/bin/env bash
# Usage: tools/run-service.sh <service-name>   e.g. tools/run-service.sh resume-service
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
[ -f "$ROOT/.env" ] && set -a && . "$ROOT/.env" && set +a
export HIRETRACK_KEYS_DIR="$ROOT/deploy/dev-keys"
mkdir -p "$ROOT/logs"
cd "$ROOT"
exec ./gradlew ":services:$1:bootRun" --args="--spring.profiles.active=local"