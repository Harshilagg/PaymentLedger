"use client";

import { use } from "react";
import Link from "next/link";
import { useAuthorizedResource } from "@/lib/use-authorized-resource";
import type { TransactionResponse } from "@/lib/types";
import { TransactionStatusBadge } from "@/components/ui/badge";
import { AmountDisplay } from "@/components/ui/amount";
import { Card, CardContent } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import { ErrorBanner } from "@/components/error-banner";
import { ReverseTransactionDialog } from "@/components/reverse-transaction-dialog";

// TransactionResponse has no lines, references, or status-history fields - the double-entry
// ledger is internal to ledger-service and never exposed over HTTP (Step 1 finding), and status
// is a single current value with no audit trail endpoint. This page shows exactly the 9 real
// fields and nothing else.
function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="flex items-center justify-between border-b border-outline-variant py-2.5 last:border-b-0">
      <span className="text-xs text-text-secondary">{label}</span>
      <span className="text-sm text-text-primary">{children}</span>
    </div>
  );
}

export default function TransactionDetailPage({ params }: PageProps<"/transactions/[id]">) {
  const { id } = use(params);
  const { data: tx, error, loading, refetch } = useAuthorizedResource<TransactionResponse>(`/transactions/${id}`);

  return (
    <div className="mx-auto w-full max-w-2xl px-6 py-8">
      <div className="mb-4 flex items-center justify-between">
        <h1 className="text-sm font-medium text-text-primary">Transaction</h1>
        {tx && tx.status === "COMPLETED" ? (
          <ReverseTransactionDialog transaction={tx} onReversed={refetch} />
        ) : null}
      </div>

      {loading ? (
        <Skeleton className="h-72 w-full" />
      ) : error ? (
        <ErrorBanner error={error} />
      ) : tx ? (
        <Card>
          <CardContent className="flex flex-col">
            <Field label="Transaction ID">
              <span className="font-mono text-xs">{tx.transactionId}</span>
            </Field>
            <Field label="Type">{tx.type}</Field>
            <Field label="Status">
              <TransactionStatusBadge status={tx.status} />
            </Field>
            <Field label="Amount">
              <AmountDisplay value={tx.amount} currency={tx.currency} size="md" />
            </Field>
            {tx.toAmount !== null && tx.toCurrency !== null ? (
              <Field label="To Amount">
                <AmountDisplay value={tx.toAmount} currency={tx.toCurrency} size="md" />
              </Field>
            ) : null}
            {tx.fromWalletId ? (
              <Field label="From Wallet">
                <Link href={`/wallets/${tx.fromWalletId}`} className="font-mono text-xs text-accent hover:underline">
                  {tx.fromWalletId}
                </Link>
              </Field>
            ) : null}
            {tx.toWalletId ? (
              <Field label="To Wallet">
                <Link href={`/wallets/${tx.toWalletId}`} className="font-mono text-xs text-accent hover:underline">
                  {tx.toWalletId}
                </Link>
              </Field>
            ) : null}
            <Field label="Created">
              <span className="font-mono text-xs">{tx.createdAt}</span>
            </Field>
          </CardContent>
        </Card>
      ) : null}
    </div>
  );
}
