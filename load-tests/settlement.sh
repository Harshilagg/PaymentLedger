#!/usr/bin/env bash
#
# Measures what the HTTP numbers cannot see.
#
# A transfer returns 202 the moment it is accepted; the money moves later, asynchronously, through
# outbox -> Kafka -> ledger-service -> Kafka -> settlement. So k6's throughput and p99 describe how
# fast the API ENQUEUES work, not how fast the system does it. If the load generator outruns the
# consumer, a backlog builds while the API still looks fast.
#
# This reports the three numbers that together tell the real story:
#   accepted        - transactions the API took in
#   settled         - transactions that actually completed
#   drain time      - how long after the load stopped before the backlog cleared
#
# Reads Postgres directly because the API exposes no aggregate count.
set -euo pipefail

LABEL="${1:-run}"
TIMEOUT="${SETTLE_TIMEOUT:-600}"
OUT_DIR="$(cd "$(dirname "$0")" && pwd)/results"
mkdir -p "$OUT_DIR"

q() {
  docker compose exec -T postgres psql -U postgres -d paymentledger -tAc "$1" 2>/dev/null | tr -d '[:space:]'
}

counts_by_status() {
  docker compose exec -T postgres psql -U postgres -d paymentledger -tAc \
    "SELECT status || '=' || count(*) FROM wallet.transaction GROUP BY status ORDER BY status" 2>/dev/null \
    | tr -d '\r' | paste -sd' ' -
}

started=$(date +%s)
pending_at_stop=$(q "SELECT count(*) FROM wallet.transaction WHERE status = 'PENDING'")
total_at_stop=$(q "SELECT count(*) FROM wallet.transaction")

echo "[$LABEL] at load stop: total=$total_at_stop pending=$pending_at_stop"
echo "[$LABEL] status breakdown at stop: $(counts_by_status)"

# Drain: how long until nothing is left in flight. This is the backlog the 202s concealed.
while :; do
  pending=$(q "SELECT count(*) FROM wallet.transaction WHERE status IN ('PENDING','COMPENSATING')")
  now=$(date +%s)
  elapsed=$(( now - started ))

  if [ "$pending" = "0" ]; then
    echo "[$LABEL] backlog drained in ${elapsed}s"
    break
  fi
  if [ "$elapsed" -ge "$TIMEOUT" ]; then
    echo "[$LABEL] DID NOT DRAIN within ${TIMEOUT}s - $pending still in flight."
    echo "[$LABEL] The consumer cannot keep up with this arrival rate; report this rather than the acceptance number alone."
    break
  fi
  sleep 2
done

drain_seconds=$(( $(date +%s) - started ))
final_breakdown="$(counts_by_status)"
settled=$(q "SELECT count(*) FROM wallet.transaction WHERE status = 'COMPLETED'")
failed=$(q "SELECT count(*) FROM wallet.transaction WHERE status = 'FAILED'")

echo "[$LABEL] final: $final_breakdown"

cat > "$OUT_DIR/settlement-$LABEL.json" <<EOF
{
  "label": "$LABEL",
  "totalAtLoadStop": $total_at_stop,
  "pendingAtLoadStop": $pending_at_stop,
  "settledTotal": $settled,
  "failedTotal": $failed,
  "backlogDrainSeconds": $drain_seconds,
  "finalStatusBreakdown": "$final_breakdown"
}
EOF
echo "[$LABEL] wrote $OUT_DIR/settlement-$LABEL.json"
