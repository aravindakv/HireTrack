#!/usr/bin/env bash
# Usage: tools/mint-jwt.sh [userId] [email] [ttlSeconds]
# Prints an RS256 JWT signed with deploy/dev-keys/private.pem. Local development only.
set -euo pipefail
USER_ID="${1:-1}"
EMAIL="${2:-dev@example.com}"
TTL="${3:-3600}"
KEY="$(cd "$(dirname "$0")/.." && pwd)/deploy/dev-keys/private.pem"

b64url() { openssl base64 -e -A | tr '+/' '-_' | tr -d '='; }

NOW=$(date +%s)
HEADER=$(printf '{"alg":"RS256","typ":"JWT","kid":"dev-key-1"}' | b64url)
PAYLOAD=$(printf '{"sub":"%s","email":"%s","iss":"hiretrack-identity","iat":%d,"exp":%d}' \
          "$USER_ID" "$EMAIL" "$NOW" "$((NOW + TTL))" | b64url)
SIGNATURE=$(printf '%s.%s' "$HEADER" "$PAYLOAD" | openssl dgst -sha256 -sign "$KEY" -binary | b64url)
printf '%s.%s.%s\n' "$HEADER" "$PAYLOAD" "$SIGNATURE"