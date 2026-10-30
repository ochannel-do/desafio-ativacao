#!/usr/bin/env bash
# Valida o fluxo HTTP real da aplicação iniciada pelo Docker Compose.
set -euo pipefail
BASE_URL="${BASE_URL:-http://localhost:8080}"
TOKEN="$("$(dirname "$0")/token-local.sh" sistema-smoke 'activation:write activation:read')"
curl --fail --silent --show-error "$BASE_URL/actuator/health/readiness" >/dev/null
EXTERNAL_ID="SMOKE-$(uuidgen)"
BODY="$(curl --fail --silent --show-error "$BASE_URL/api/v1/requests" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d "{\"externalId\":\"$EXTERNAL_ID\",\"customerId\":\"C-SMOKE\",\"productCode\":\"INTERNET-500\"}")"
REQUEST_ID="$(python3 -c 'import sys,json; print(json.load(sys.stdin)["id"])' <<< "$BODY")"
for _ in $(seq 1 30); do
  RESULT="$(curl --fail --silent --show-error "$BASE_URL/api/v1/requests/$REQUEST_ID" -H "Authorization: Bearer $TOKEN")"
  STATUS="$(python3 -c 'import sys,json; print(json.load(sys.stdin)["status"])' <<< "$RESULT")"
  if [ "$STATUS" = COMPLETED ]; then
    printf 'Ativação %s concluída pelo fluxo Kafka.\n' "$REQUEST_ID"
    exit 0
  fi
  if [ "$STATUS" = FAILED ]; then printf 'Ativação falhou.\n' >&2; exit 1; fi
  sleep 1
done
printf 'Ativação não concluiu em 30 segundos.\n' >&2
exit 1
