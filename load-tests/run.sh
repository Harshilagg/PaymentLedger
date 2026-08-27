#!/usr/bin/env bash
#
# Runs one k6 scenario end to end: reset -> seed -> measure -> settlement.
#
#   ./load-tests/run.sh baseline
#   ./load-tests/run.sh contention
#   ./load-tests/run.sh read-heavy
#   ./load-tests/run.sh cross-currency
#   ./load-tests/run.sh all
#
# The reset is not optional and not a convenience. Each measured run leaves thousands of rows in
# transaction and idempotency_record; a later run against those larger tables would look slower for
# reasons that have nothing to do with the change being measured. Skipping it is how you end up
# attributing a regression to a cache that did not cause it. Set SKIP_RESET=1 only when you are
# deliberately measuring warm/full-table behaviour and intend to say so.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LOAD_DIR="$ROOT/load-tests"
NETWORK="${NETWORK:-paymentledger_default}"
K6_IMAGE="${K6_IMAGE:-grafana/k6:latest}"
SCENARIOS=("baseline" "contention" "read-heavy" "cross-currency")

cd "$ROOT"
mkdir -p "$LOAD_DIR/results"

reset_stack() {
  echo "==> Resetting stack (down -v: drops the Postgres volume, so every table starts empty)"
  docker compose down -v
  docker compose up -d
  echo "==> Waiting for wallet-service to come up"
  for _ in $(seq 1 90); do
    if curl -sf http://localhost:8081/actuator/health >/dev/null 2>&1; then
      echo "==> wallet-service healthy"; return 0
    fi
    sleep 2
  done
  echo "!! wallet-service did not become healthy" >&2
  exit 1
}

run_one() {
  local scenario="$1"
  echo
  echo "############################################################"
  echo "# $scenario"
  echo "############################################################"

  if [ "${SKIP_RESET:-0}" != "1" ]; then
    reset_stack
    bash "$LOAD_DIR/seed.sh"
  else
    echo "==> SKIP_RESET=1: reusing existing state (numbers are not comparable to a clean run)"
  fi

  # k6 joins the compose network so it can address wallet-service by service name. --quiet keeps
  # the progress bar out of the captured output; the JSON summary is written to results/.
  docker run --rm \
    --network "$NETWORK" \
    -v "$LOAD_DIR:/scripts" \
    -w /scripts \
    --user root \
    "$K6_IMAGE" run "/scripts/scenarios/$scenario.js" \
    --quiet --no-usage-report \
    > "$LOAD_DIR/results/$scenario.summary.json" || true

  echo "==> k6 finished; measuring settlement"
  bash "$LOAD_DIR/settlement.sh" "$scenario"
}

if [ "${1:-all}" = "all" ]; then
  for s in "${SCENARIOS[@]}"; do run_one "$s"; done
else
  run_one "$1"
fi

echo
echo "==> Results in $LOAD_DIR/results/"
ls -1 "$LOAD_DIR/results/"
