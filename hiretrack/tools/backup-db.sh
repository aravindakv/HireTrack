#!/usr/bin/env bash
# Writes backups/hiretrack-<timestamp>.sql.gz (all service schemas).
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p backups
FILE="backups/hiretrack-$(date +%Y%m%d-%H%M%S).sql.gz"
docker compose -f deploy/docker-compose.yml exec -T postgres pg_dump -U hiretrack -d hiretrack | gzip > "$FILE"
echo "Backup written to $FILE"