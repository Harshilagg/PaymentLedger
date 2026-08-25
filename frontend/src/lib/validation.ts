// Mirrors backend validation exactly so the client never sends a request the API would reject
// on shape alone (see CreateWalletRequest, InitiateDepositRequest, InitiateTransferRequest).

const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export function isValidUuid(value: string): boolean {
  return UUID_PATTERN.test(value.trim());
}

// Mirrors CreateWalletRequest's @Pattern(regexp = "[A-Z]{3}").
const CURRENCY_PATTERN = /^[A-Z]{3}$/;

export function isValidCurrencyCode(value: string): boolean {
  return CURRENCY_PATTERN.test(value.trim());
}

// Mirrors @DecimalMin(value = "0.00", inclusive = false) on every amount field: a non-negative
// decimal string with at least one nonzero digit. Checked as a string, never parsed as a float,
// so it can't misjudge a value near the boundary of float64 precision.
const DECIMAL_SHAPE_PATTERN = /^\d+(\.\d+)?$/;

export function isPositiveDecimal(value: string): boolean {
  const trimmed = value.trim();
  return DECIMAL_SHAPE_PATTERN.test(trimmed) && /[1-9]/.test(trimmed);
}
