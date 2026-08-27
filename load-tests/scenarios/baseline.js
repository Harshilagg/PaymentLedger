import { BASE_URL, loadSeed, steppedScenarios, steppedThresholds, stepsFrom, THROUGHPUT_STEPS } from '../lib/config.js';
import { transfer } from '../lib/api.js';

/**
 * Headline throughput with contention removed, so this is the ceiling the other scenarios are read
 * against.
 *
 * Each VU owns its own source wallet and sends to a RECEIVE-ONLY wallet that is never anybody's
 * source. Both halves matter. An earlier version paired VUs in a ring - VU i sourced from users[i-1]
 * and sent to users[i] - which made every wallet simultaneously one VU's source and another's
 * destination, so a reservation and a settlement-credit wrote the same row. That measured 0.52%
 * retry-exhaustion at 5 VUs: small, but it meant the "no contention" baseline contained contention.
 *
 * This measures ACCEPTANCE, not settlement: the endpoint answers 202 and the saga finishes
 * afterwards. See docs/PERFORMANCE.md.
 */
const seed = loadSeed();

const STEPS = stepsFrom(THROUGHPUT_STEPS);

export const options = {
  scenarios: steppedScenarios('run', STEPS),
  thresholds: steppedThresholds(STEPS),
  summaryTrendStats: ['avg', 'p(50)', 'p(95)', 'p(99)', 'max'],
};

export function run() {
  const users = seed.users;
  const source = users[(__VU - 1) % users.length];
  const destination = seed.receiveOnlyWalletIds[(__VU - 1) % seed.receiveOnlyWalletIds.length];

  transfer(BASE_URL, source.token, source.wallets.USD, destination, 'baseline');
}

export function handleSummary(data) {
  return {
    stdout: JSON.stringify(data, null, 2),
    '/scripts/results/baseline.json': JSON.stringify(data, null, 2),
  };
}
