#!/usr/bin/env bash
# Creates a 2048-bit RSA key pair for local development only.
set -euo pipefail
DIR="$(cd "$(dirname "$0")/.." && pwd)/deploy/dev-keys"
mkdir -p "$DIR"
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$DIR/private.pem"
openssl rsa -in "$DIR/private.pem" -pubout -out "$DIR/public.pem"
# 644, not 600: identity-service runs as uid 10001 in Docker (Phase 12) and reads this file through a
# read-only mount. Local-only dev key, git-ignored; production keys come from Secrets Manager.
chmod 644 "$DIR/private.pem"
echo "Keys written to $DIR"