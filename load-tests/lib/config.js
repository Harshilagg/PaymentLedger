// Shared configuration for every scenario.
//
// k6 runs inside its own container joined to the compose network, so it addresses wallet-service by
// its compose service name. localhost would resolve to the k6 container itself.
export const BASE_URL = __ENV.BASE_URL || 'http://wallet-service:8081';

// The VU ladders. Each rung is held for STEP_SECONDS, of which the first WARMUP_SECONDS is tagged
// phase:warmup and excluded from the reported numbers - cold JIT in the JVM otherwise makes the
// lowest rungs look far worse than steady state.
//
// Two different ladders because the scenarios are looking for different things.
//
// THROUGHPUT_STEPS climbs until the box stops giving more. CONTENTION_STEPS starts at 1 VU and
// climbs slowly: a pilot run showed single-wallet contention already producing ~10% retry-exhaustion
// at 2 VUs, so a ladder starting at 5 would begin above the interesting region and report a
// breakpoint that had already passed. Measure where it actually breaks, not where you assumed.
export const THROUGHPUT_STEPS = [5, 10, 20, 40, 80];
export const CONTENTION_STEPS = [1, 2, 3, 5, 8, 12, 20, 40];

// Overridable so the harness can be smoke-tested in seconds without editing it. The committed
// defaults are the ones docs/PERFORMANCE.md reports against; any override must be stated alongside
// the numbers it produced.
export function stepsFrom(defaults) {
  return (__ENV.STEPS ? __ENV.STEPS.split(',') : defaults.map(String))
    .map((v) => parseInt(v.trim(), 10));
}
export const WARMUP_SECONDS = parseInt(__ENV.WARMUP_SECONDS || '15', 10);
export const MEASURE_SECONDS = parseInt(__ENV.MEASURE_SECONDS || '45', 10);
export const STEP_SECONDS = WARMUP_SECONDS + MEASURE_SECONDS; // 60s held per step by default

// Small relative to the seeded balances, so a long run cannot drain a wallet and start returning
// 422 insufficient-funds - which would silently turn a contention measurement into a funding one.
export const TRANSFER_AMOUNT = '1.00';

/**
 * Builds one k6 scenario per VU rung, split into a warm-up phase and a measured phase that run
 * back to back. Splitting them into separate scenarios rather than filtering after the fact means
 * the discard is structural: no warm-up sample ever carries phase:measure.
 */
export function steppedScenarios(execFn, steps) {
  const scenarios = {};
  steps.forEach((vus, index) => {
    const start = index * STEP_SECONDS;
    const step = String(vus).padStart(3, '0');

    scenarios[`s${step}_warmup`] = {
      executor: 'constant-vus',
      vus,
      duration: `${WARMUP_SECONDS}s`,
      startTime: `${start}s`,
      exec: execFn,
      tags: { step, phase: 'warmup' },
    };
    scenarios[`s${step}_measure`] = {
      executor: 'constant-vus',
      vus,
      duration: `${MEASURE_SECONDS}s`,
      startTime: `${start + WARMUP_SECONDS}s`,
      exec: execFn,
      tags: { step, phase: 'measure' },
    };
  });
  return scenarios;
}

/**
 * k6 only surfaces per-tag breakdowns in the summary for metrics that a threshold names, so every
 * per-step number we want to report has to be declared here. The thresholds themselves are
 * deliberately non-failing (`rate<=1`, `count>=0`): this suite exists to measure, not to gate a
 * build, and a red threshold would obscure the reading we came for.
 */
export function steppedThresholds(steps) {
  const thresholds = {};
  steps.forEach((vus) => {
    const step = String(vus).padStart(3, '0');
    const sel = `{step:${step},phase:measure}`;
    thresholds[`http_req_duration${sel}`] = ['p(50)>=0', 'p(95)>=0', 'p(99)>=0'];
    thresholds[`http_reqs${sel}`] = ['count>=0'];
    thresholds[`accepted${sel}`] = ['count>=0'];
    thresholds[`retry_exhausted_503${sel}`] = ['count>=0'];
    thresholds[`insufficient_funds_422${sel}`] = ['count>=0'];
    thresholds[`idempotency_conflict_409${sel}`] = ['count>=0'];
    thresholds[`other_error${sel}`] = ['count>=0'];
  });
  return thresholds;
}

/** Loads the seed file written by seed.sh. Fails loudly rather than running against nothing. */
export function loadSeed() {
  const raw = open('/scripts/seed.json');
  const seed = JSON.parse(raw);
  if (!seed.users || seed.users.length === 0) {
    throw new Error('seed.json has no users - run load-tests/seed.sh first');
  }
  return seed;
}
