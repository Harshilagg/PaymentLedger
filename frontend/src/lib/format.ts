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
