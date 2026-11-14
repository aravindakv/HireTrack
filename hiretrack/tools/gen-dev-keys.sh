#!/usr/bin/env bash
# Creates a 2048-bit RSA key pair for local development only.
set -euo pipefail
DIR="$(cd "$(dirname "$0")/.." && pwd)/deploy/dev-keys"
mkdir -p "$DIR"
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$DIR/private.pem"
openssl rsa -in "$DIR/private.pem" -pubout -out "$DIR/public.pem"
chmod 600 "$DIR/private.pem"
echo "Keys written to $DIR"