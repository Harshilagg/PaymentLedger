import http from 'k6/http';
import { Counter } from 'k6/metrics';
import { TRANSFER_AMOUNT } from './config.js';

// Broken out by status because the failures mean completely different things here:
//   503 - OptimisticLockRetrier exhausted its 5 attempts. THE signal for the contention scenario.
//   422 - insufficient funds. If this appears, the wallet was underfunded and the run is invalid.
//   409 - idempotency conflict. Should never appear; if it does, keys are colliding and the run
//         is measuring the dedupe path instead of the transfer path.
export const accepted = new Counter('accepted');
export const retryExhausted503 = new Counter('retry_exhausted_503');
export const insufficientFunds422 = new Counter('insufficient_funds_422');
export const idempotencyConflict409 = new Counter('idempotency_conflict_409');
export const otherError = new Counter('other_error');

// Unique per iteration, per VU, per run - by construction rather than by trusting an RNG not to
// collide. A repeated Idempotency-Key would return the first response from the dedupe cache and we
// would be measuring that path instead of a real transfer.
const RUN_ID = `${Date.now()}-${Math.floor(Math.random() * 1e6)}`;

export function idempotencyKey(label) {
  return `perf-${RUN_ID}-${label}-${__VU}-${__ITER}`;
}

function authHeaders(token, extra) {
  return Object.assign(
    { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
    extra || {},
  );
}

/**
 * Records the outcome of a transfer attempt. Note 202, not 200: the endpoint accepts the transfer
 * and the saga settles it afterwards, so this counts ACCEPTANCE - see docs/PERFORMANCE.md on why
 * that is not the same as the money having moved.
 */
export function recordTransfer(res, tags) {
  if (res.status === 202) {
    accepted.add(1, tags);
  } else if (res.status === 503) {
    retryExhausted503.add(1, tags);
  } else if (res.status === 422) {
    insufficientFunds422.add(1, tags);
  } else if (res.status === 409) {
    idempotencyConflict409.add(1, tags);
  } else {
    otherError.add(1, tags);
  }
}

export function transfer(baseUrl, token, fromWalletId, toWalletId, label, tags) {
  const res = http.post(
    `${baseUrl}/wallets/${fromWalletId}/transfers`,
    JSON.stringify({ toWalletId, amount: TRANSFER_AMOUNT }),
    {
      headers: authHeaders(token, { 'Idempotency-Key': idempotencyKey(label) }),
      tags: Object.assign({ op: 'transfer' }, tags || {}),
    },
  );
  recordTransfer(res, tags);
  return res;
}

export function getWallet(baseUrl, token, walletId, tags) {
  return http.get(`${baseUrl}/wallets/${walletId}`, {
    headers: authHeaders(token),
    tags: Object.assign({ op: 'get_wallet' }, tags || {}),
  });
}

export function listTransactions(baseUrl, token, walletId, tags) {
  return http.get(`${baseUrl}/wallets/${walletId}/transactions?page=0&size=20`, {
    headers: authHeaders(token),
    tags: Object.assign({ op: 'list_transactions' }, tags || {}),
  });
}
