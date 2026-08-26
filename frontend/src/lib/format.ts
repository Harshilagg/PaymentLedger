// Shortens a UUID for display in tight places like a breadcrumb — "24e96111…e4ecce". Callers must
// keep the full value reachable (a title attribute), since a truncated id is unusable for copying
// or for matching against what the API returned. Anything too short to gain from shortening, or
// that isn't the expected length, is returned untouched rather than mangled.
const TRUNCATE_HEAD = 8;
const TRUNCATE_TAIL = 6;

export function truncateId(value: string): string {
  if (value.length <= TRUNCATE_HEAD + TRUNCATE_TAIL + 1) return value;
  return `${value.slice(0, TRUNCATE_HEAD)}…${value.slice(-TRUNCATE_TAIL)}`;
}

// Formats a decimal amount string for display without ever routing it through a float64 —
// the value came from the API already rounded to the currency's real minor-unit precision
// (see MoneyMapper on the backend), so this only adds thousands separators; it never rounds,
// truncates, or re-derives decimal places.
export function formatAmount(value: string): string {
  const negative = value.startsWith("-");
  const unsigned = negative ? value.slice(1) : value;
  const [integerPart, fractionPart] = unsigned.split(".");
  const withSeparators = integerPart.replace(/\B(?=(\d{3})+(?!\d))/g, ",");
  const formatted = fractionPart ? `${withSeparators}.${fractionPart}` : withSeparators;
  return negative ? `-${formatted}` : formatted;
}
