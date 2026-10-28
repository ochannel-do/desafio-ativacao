#!/usr/bin/env bash
# Gera um JWT HS256 aceito pelo perfil local, simulando o servidor de autorização.
# Uso: scripts/token-local.sh [client_id] [escopos]
#   scripts/token-local.sh sistema-a "activation:write activation:read"
set -euo pipefail
CLIENT_ID="${1:-sistema-a}"
SCOPES="${2:-activation:write activation:read}"
KEY="${AUTH_HMAC_KEY:-chave-local-de-desenvolvimento-com-32-bytes}"
NOW=$(date +%s)
b64() { openssl base64 -A | tr '+/' '-_' | tr -d '='; }
HEADER=$(printf '{"alg":"HS256","typ":"JWT"}' | b64)
PAYLOAD=$(printf '{"iss":"http://localhost/local","aud":"ativacao-api","sub":"%s","client_id":"%s","scope":"%s","iat":%d,"exp":%d}' \
  "$CLIENT_ID" "$CLIENT_ID" "$SCOPES" "$NOW" "$((NOW + 3600))" | b64)
SIG=$(printf '%s.%s' "$HEADER" "$PAYLOAD" | openssl dgst -sha256 -hmac "$KEY" -binary | b64)
echo "$HEADER.$PAYLOAD.$SIG"
