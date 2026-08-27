import { BASE_URL, loadSeed, steppedScenarios, steppedThresholds, stepsFrom, THROUGHPUT_STEPS } from '../lib/config.js';
import { transfer } from '../lib/api.js';

/**
 * The only scenario that exercises the exchange_rate lookup, and therefore the only one whose
 * numbers can move when the FX cache lands.
 *
 * FxConverter short-circuits when source and destination currency match and never touches the
 * table, so a same-currency scenario would give that cache nothing to cache and would honestly
 * report no change. Every transfer here crosses currencies against the six pairs seeded by V3.
 *
 * Otherwise identical in shape to baseline: distinct source per VU, no shared rows, so any
 * difference between this and baseline is the FX conversion path itself.
 */
const seed = loadSeed();

const STEPS = stepsFrom(THROUGHPUT_STEPS);

export const options = {
  scenarios: steppedScenarios('run', STEPS),
  thresholds: steppedThresholds(STEPS),
  summaryTrendStats: ['avg', 'p(50)', 'p(95)', 'p(99)', 'max'],
};

// Seeded directed pairs (V3__exchange_rates.sql). Sources are always USD wallets here; the
// destination currency alternates so more than one cache key is exercised.
const DESTINATION_CURRENCIES = ['EUR', 'GBP'];

export function run() {
  const users = seed.users;
  const source = users[(__VU - 1) % users.length];
  const destination = users[__VU % users.length];
  const currency = DESTINATION_CURRENCIES[__ITER % DESTINATION_CURRENCIES.length];

  transfer(BASE_URL, source.token, source.wallets.USD, destination.wallets[currency], 'crossfx');
}

export function handleSummary(data) {
  return {
    stdout: JSON.stringify(data, null, 2),
    '/scripts/results/cross-currency.json': JSON.stringify(data, null, 2),
  };
}
