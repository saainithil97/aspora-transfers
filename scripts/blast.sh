#!/usr/bin/env bash
# Fire N concurrent POST /transfers and summarise what came back.
#
#   scripts/blast.sh N [--same-key] [--amount MINOR] [--from ACCOUNT_ID] [--type PAYOUT|INTERNAL] [--to ACCOUNT_ID]
#
# Every request gets a fresh Idempotency-Key unless --same-key is given.
# All N requests are sent from a single curl process (--parallel), so they leave within a millisecond
# or two of each other. BASE_URL defaults to http://localhost:8080.
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
ALICE=11111111-1111-1111-1111-111111111111
BOB=22222222-2222-2222-2222-222222222222

if [[ $# -lt 1 || ! "$1" =~ ^[0-9]+$ || "$1" -lt 1 || "$1" -gt 300 ]]; then
  echo "usage: $0 N(1-300) [--same-key] [--amount MINOR] [--from ACCOUNT_ID] [--type PAYOUT|INTERNAL] [--to ACCOUNT_ID]" >&2
  exit 2
fi
N="$1"; shift

SAME_KEY=0
AMOUNT=100000
FROM="$ALICE"
TO="$BOB"
TYPE=INTERNAL
while [[ $# -gt 0 ]]; do
  case "$1" in
    --same-key) SAME_KEY=1; shift ;;
    --amount)   AMOUNT="$2"; shift 2 ;;
    --from)     FROM="$2"; shift 2 ;;
    --to)       TO="$2"; shift 2 ;;
    --type)     TYPE="$2"; shift 2 ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
done

case "$TYPE" in
  INTERNAL) BODY="{\"type\":\"INTERNAL\",\"fromAccountId\":\"$FROM\",\"toAccountId\":\"$TO\",\"amountMinor\":$AMOUNT,\"currency\":\"INR\"}" ;;
  PAYOUT)   BODY="{\"type\":\"PAYOUT\",\"fromAccountId\":\"$FROM\",\"beneficiaryRef\":\"BENE-BLAST\",\"amountMinor\":$AMOUNT,\"currency\":\"INR\"}" ;;
  *) echo "type must be PAYOUT or INTERNAL" >&2; exit 2 ;;
esac
ESCAPED_BODY=${BODY//\"/\\\"}   # curl config files want \" inside double-quoted values

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
CFG="$TMP/requests.curl"
FIXED_KEY=$(uuidgen | tr '[:upper:]' '[:lower:]')

# One config section per request; curl runs them all at once.
for i in $(seq 1 "$N"); do
  if [[ $SAME_KEY -eq 1 ]]; then key="$FIXED_KEY"; else key=$(uuidgen | tr '[:upper:]' '[:lower:]'); fi
  [[ $i -gt 1 ]] && echo "next" >> "$CFG"
  cat >> "$CFG" <<CURL
url = "$BASE_URL/transfers"
request = "POST"
header = "Content-Type: application/json"
header = "Idempotency-Key: $key"
data = "$ESCAPED_BODY"
output = "$TMP/$i.body"
write-out = "%{http_code} $i\n"
CURL
done

echo "POST /transfers x$N  type=$TYPE amount=$AMOUNT from=$FROM$( [[ $SAME_KEY -eq 1 ]] && echo ' (same Idempotency-Key)' )"
curl -s --parallel --parallel-immediate --parallel-max "$N" -K "$CFG" > "$TMP/codes"

RESULTS=$(sort -k2 -n "$TMP/codes" | while read -r code i; do
  body=$(cat "$TMP/$i.body" 2>/dev/null || true)
  id=$(printf '%s' "$body" | sed -n 's/.*"id":"\([^"]*\)".*/\1/p')
  err=$(printf '%s' "$body" | sed -n 's/.*"error":"\([^"]*\)".*/\1/p')
  echo "$code ${id:-${err:-$body}}"
done)
echo "$RESULTS"
echo
echo "--- status codes ---"
echo "$RESULTS" | awk '{print $1}' | sort | uniq -c
echo "--- distinct transfer ids among 2xx ---"
echo "$RESULTS" | awk '$1 ~ /^2/ {print $2}' | sort -u | wc -l | tr -d ' '
echo "--- account $FROM ---"
curl -s "$BASE_URL/accounts/$FROM" | sed -n 's/.*"balanceMinor":\([0-9]*\).*/balanceMinor: \1/p'
