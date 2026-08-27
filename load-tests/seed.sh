#!/usr/bin/env bash
#
# Creates the users, accounts and wallets the k6 scenarios run against, and writes their ids and
# tokens to seed.json.
#
# Deliberately bash + curl rather than k6. k6 can only write files from handleSummary, which runs
# after the test and cannot make HTTP requests, and each VU has its own JS runtime so state built
# during a run is not visible there. Seeding in k6 would mean fighting that for no benefit - and
# keeping it out of k6 also keeps setup work out of the measured numbers entirely.
#
# Runs on the HOST against the published port, unlike the scenarios which run inside the compose
# network. Adds no Java or Node dependency: curl and python3 only.
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8081}"
USERS="${USERS:-80}"          # = max THROUGHPUT_STEPS rung, so baseline VUs never share a source
DESTINATIONS="${DESTINATIONS:-80}"   # = max rung of EITHER ladder: one distinct destination per VU
DEPOSIT="${DEPOSIT:-10000000.00}"    # far more than any run can spend, so 422 never masks 503
OUT="$(cd "$(dirname "$0")" && pwd)/seed.json"

say() { printf '%s\n' "$*" >&2; }
json() { python3 -c "import sys,json;print(json.load(sys.stdin)$1)"; }

register() {
  curl -sS -X POST "$BASE_URL/auth/register" \
    -H 'Content-Type: application/json' \
    -d "{\"email\":\"$1\",\"password\":\"perf-password-123\"}"
}

post_auth() { # path, token, body
  if [ -n "${3:-}" ]; then
    curl -sS -X POST "$BASE_URL$1" -H "Authorization: Bearer $2" \
      -H 'Content-Type: application/json' -d "$3"
  else
    curl -sS -X POST "$BASE_URL$1" -H "Authorization: Bearer $2"
  fi
}

RUN_TAG="$(date +%s)-$RANDOM"
say "Seeding against $BASE_URL (users=$USERS destinations=$DESTINATIONS)"

# --- the pool the baseline / read-heavy / cross-currency scenarios use -------------------------
USER_ROWS=()
for i in $(seq 1 "$USERS"); do
  email="perf-u${i}-${RUN_TAG}@example.com"
  token=$(register "$email" | json "['accessToken']")
  account=$(post_auth "/accounts" "$token" "" | json "['id']")

  usd=$(post_auth "/accounts/$account/wallets" "$token" '{"currency":"USD"}' | json "['id']")
  eur=$(post_auth "/accounts/$account/wallets" "$token" '{"currency":"EUR"}' | json "['id']")
  gbp=$(post_auth "/accounts/$account/wallets" "$token" '{"currency":"GBP"}' | json "['id']")

  # Fire the deposit now; settlement is polled for later, after every deposit is in flight, so the
  # sagas overlap instead of being waited on one at a time.
  curl -sS -o /dev/null -X POST "$BASE_URL/wallets/$usd/deposits" \
    -H "Authorization: Bearer $token" -H 'Content-Type: application/json' \
    -H "Idempotency-Key: seed-$RUN_TAG-u$i" -d "{\"amount\":$DEPOSIT}"

  USER_ROWS+=("{\"email\":\"$email\",\"token\":\"$token\",\"accountId\":\"$account\",\"wallets\":{\"USD\":\"$usd\",\"EUR\":\"$eur\",\"GBP\":\"$gbp\"}}")
  [ $((i % 20)) -eq 0 ] && say "  ...$i/$USERS users"
done

# --- receive-only wallets, shared by every scenario --------------------------------------------
# These are ONLY ever transferred INTO, never out of. That distinction is the whole point: if a
# wallet were both a source and a destination, a reservation and a settlement-credit would write the
# same row and the "no contention" scenarios would quietly be measuring contention.
say "Seeding $DESTINATIONS receive-only wallets"
hot_email="perf-hot-${RUN_TAG}@example.com"
HOT_TOKEN=$(register "$hot_email" | json "['accessToken']")
hot_account=$(post_auth "/accounts" "$HOT_TOKEN" "" | json "['id']")
HOT_SOURCE=$(post_auth "/accounts/$hot_account/wallets" "$HOT_TOKEN" '{"currency":"USD"}' | json "['id']")
curl -sS -o /dev/null -X POST "$BASE_URL/wallets/$HOT_SOURCE/deposits" \
  -H "Authorization: Bearer $HOT_TOKEN" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: seed-$RUN_TAG-hot" -d "{\"amount\":$DEPOSIT}"

# One wallet per account: uq_wallet_account_currency means N USD destinations need N accounts.
# They only ever receive, so they need no funding.
DEST_IDS=()
for i in $(seq 1 "$DESTINATIONS"); do
  acct=$(post_auth "/accounts" "$HOT_TOKEN" "" | json "['id']")
  w=$(post_auth "/accounts/$acct/wallets" "$HOT_TOKEN" '{"currency":"USD"}' | json "['id']")
  DEST_IDS+=("\"$w\"")
  [ $((i % 40)) -eq 0 ] && say "  ...$i/$DESTINATIONS destinations"
done

# --- wait for the money to actually land -------------------------------------------------------
# A deposit returns 202 and credits on settlement, so right now every balance is still 0. Starting
# the run here would produce a wall of 422s and scenario 2 would measure funding, not contention.
say "Waiting for seed deposits to settle through the saga..."
deadline=$(( $(date +%s) + 300 ))
while :; do
  pending=$(docker compose exec -T postgres psql -U postgres -d paymentledger -tAc \
    "SELECT count(*) FROM wallet.transaction WHERE status = 'PENDING'" 2>/dev/null | tr -d '[:space:]')
  [ -z "$pending" ] && pending="?"
  if [ "$pending" = "0" ]; then say "  all seed deposits settled"; break; fi
  if [ "$(date +%s)" -ge "$deadline" ]; then
    say "  TIMED OUT with $pending still PENDING - the saga is not keeping up; do not trust a run from this seed"
    exit 1
  fi
  say "  $pending still pending..."
  sleep 3
done

# Confirm the hot wallet really is funded before declaring success.
hot_balance=$(curl -sS "$BASE_URL/wallets/$HOT_SOURCE" -H "Authorization: Bearer $HOT_TOKEN" | json "['balance']")
say "Contention source wallet balance: $hot_balance"

printf '{\n  "baseUrl": "%s",\n  "users": [%s],\n  "receiveOnlyWalletIds": [%s],\n  "contention": {"token":"%s","sourceWalletId":"%s"}\n}\n' \
  "$BASE_URL" \
  "$(IFS=,; echo "${USER_ROWS[*]}")" \
  "$(IFS=,; echo "${DEST_IDS[*]}")" \
  "$HOT_TOKEN" "$HOT_SOURCE" > "$OUT"

python3 -c "import json,sys; d=json.load(open('$OUT')); print(f\"seed.json: {len(d['users'])} users, {len(d['receiveOnlyWalletIds'])} receive-only wallets\")" >&2
