import { BASE_URL, loadSeed, steppedScenarios, steppedThresholds, stepsFrom, CONTENTION_STEPS } from '../lib/config.js';
import { transfer } from '../lib/api.js';

/**
 * The headline measurement: where bounded optimistic-lock retries start losing.
 *
 * Every VU transfers OUT OF THE SAME source wallet, so they all contend on that one row's @Version
 * column. Each VU transfers INTO ITS OWN destination wallet - if they shared a destination there
 * would be two contended rows and a 503 could not be attributed to the source.
 *
 * A 503 here is OptimisticLockRetrier giving up after 5 attempts (GlobalExceptionHandler maps
 * ObjectOptimisticLockingFailureException to 503). Unlike the acceptance latency this scenario also
 * records, that number is NOT distorted by the saga: the reservation and the version conflict both
 * happen synchronously, inside the transaction, before the 202 is written. The 503 curve is real.
 */
const seed = loadSeed();

const STEPS = stepsFrom(CONTENTION_STEPS);

export const options = {
  scenarios: steppedScenarios('contend', STEPS),
  thresholds: steppedThresholds(STEPS),
  summaryTrendStats: ['avg', 'p(50)', 'p(95)', 'p(99)', 'max'],
};

export function contend() {
  const hot = seed.contention;
  // Distinct receive-only destination per VU; wraps only if VUs exceed the seeded pool, which
  // seed.sh sizes to the largest rung so in a normal run it never does.
  const destination = seed.receiveOnlyWalletIds[(__VU - 1) % seed.receiveOnlyWalletIds.length];

  // No step/phase tags passed here on purpose: k6 applies the scenario's own tags to every metric
  // emitted inside it, custom counters included, so they arrive already labelled.
  transfer(BASE_URL, hot.token, hot.sourceWalletId, destination, 'contend');
}

export function handleSummary(data) {
  return {
    stdout: JSON.stringify(data, null, 2),
    '/scripts/results/contention.json': JSON.stringify(data, null, 2),
  };
}
