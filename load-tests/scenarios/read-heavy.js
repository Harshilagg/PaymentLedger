import { BASE_URL, loadSeed, steppedScenarios, steppedThresholds, stepsFrom, THROUGHPUT_STEPS } from '../lib/config.js';
import { transfer, getWallet, listTransactions } from '../lib/api.js';

/**
 * Roughly 90% reads to 10% writes, which is far closer to how a wallet API is actually used than a
 * pure write benchmark. Reads are synchronous and fully served before the response, so unlike the
 * transfer path their latency is the real end-to-end cost with nothing deferred to the saga.
 *
 * This is also the scenario most sensitive to table size, which is why a state reset before every
 * measured run is mandatory - see docs/PERFORMANCE.md.
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
  const user = users[(__VU - 1) % users.length];
  const roll = Math.random();

  if (roll < 0.45) {
    getWallet(BASE_URL, user.token, user.wallets.USD);
  } else if (roll < 0.9) {
    listTransactions(BASE_URL, user.token, user.wallets.USD);
  } else {
    const destination = seed.receiveOnlyWalletIds[(__VU - 1) % seed.receiveOnlyWalletIds.length];
    transfer(BASE_URL, user.token, user.wallets.USD, destination, 'readheavy');
  }
}

export function handleSummary(data) {
  return {
    stdout: JSON.stringify(data, null, 2),
    '/scripts/results/read-heavy.json': JSON.stringify(data, null, 2),
  };
}
