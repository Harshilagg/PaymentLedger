// Mirrors the wallet-service API exactly (see wallet-service/src/main/java/.../api/dto and domain).
// No field here may be invented — every name matches a real response field or enum constant.
//
// Money fields (balance/reserved/available/amount/toAmount) are typed as string even though
// Jackson emits BigDecimal as a bare JSON number on the wire: the API client parses responses
// with a decimal-preserving reader (see lib/api.ts) instead of JSON.parse, so these values never
// pass through a float64 and can't be silently rounded.

export type WalletStatus = "ACTIVE" | "FROZEN" | "CLOSED";

export type TransactionType = "DEPOSIT" | "WITHDRAWAL" | "TRANSFER" | "REVERSAL";

export type TransactionStatus = "PENDING" | "COMPLETED" | "COMPENSATING" | "FAILED";

export interface AccountResponse {
  id: string;
  ownerId: string;
  createdAt: string;
}

export interface WalletResponse {
  id: string;
  accountId: string;
  currency: string;
  status: WalletStatus;
  balance: string;
  reserved: string;
  available: string;
  createdAt: string;
}

export interface TransactionResponse {
  transactionId: string;
  type: TransactionType;
  status: TransactionStatus;
  amount: string;
  currency: string;
  toAmount: string | null;
  toCurrency: string | null;
  fromWalletId: string | null;
  toWalletId: string | null;
  createdAt: string;
}

/**
 * The double-entry rows behind a transaction, from GET /transactions/{id}/ledger-entries.
 * `amount` is a string for the same reason every other money field is - see the note at the top.
 */
export interface LedgerEntryResponse {
  id: string;
  walletId: string;
  direction: "DEBIT" | "CREDIT";
  amount: string;
  currency: string;
  createdAt: string;
}

/**
 * Spring's Page envelope. Only the stable top-level members are declared - the nested `pageable`
 * and `sort` objects are an implementation detail of Spring's serialization and are deliberately
 * not relied on here.
 */
export interface PageResponse<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
  first: boolean;
  last: boolean;
}
