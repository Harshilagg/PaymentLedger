import type { TransactionResponse } from "@/lib/types";

/**
 * Derives which side of a transaction a given wallet is on, purely from fromWalletId/toWalletId -
 * there is no "direction" field on TransactionResponse, so this is inferred, not invented.
 * Cross-currency transfers settle the "to" side in toAmount/toCurrency (Step 1 finding), so a
 * credit uses those fields when present instead of amount/currency.
 */
export function amountForWallet(
  tx: TransactionResponse,
  walletId: string,
): { value: string; currency: string; sign: "debit" | "credit" } | null {
  if (tx.fromWalletId === walletId) {
    return { value: tx.amount, currency: tx.currency, sign: "debit" };
  }
  if (tx.toWalletId === walletId) {
    const usesCrossCurrency = tx.toAmount !== null && tx.toCurrency !== null;
    return {
      value: usesCrossCurrency ? tx.toAmount! : tx.amount,
      currency: usesCrossCurrency ? tx.toCurrency! : tx.currency,
      sign: "credit",
    };
  }
  return null;
}
