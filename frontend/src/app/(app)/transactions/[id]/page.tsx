"use client";

import { use } from "react";
import Link from "next/link";
import { useAuthorizedResource } from "@/lib/use-authorized-resource";
import type { LedgerEntryResponse, TransactionResponse } from "@/lib/types";
import { TransactionStatusBadge } from "@/components/ui/badge";
import { AmountDisplay } from "@/components/ui/amount";
import { Card, CardContent } from "@/components/ui/card";
import { Table, TableHeader, TableBody, TableRow, TableHead, TableCell } from "@/components/ui/table";
import { Skeleton } from "@/components/ui/skeleton";
import { EmptyState } from "@/components/ui/empty-state";
import { ErrorBanner } from "@/components/error-banner";
import { ReverseTransactionDialog } from "@/components/reverse-transaction-dialog";
import { Breadcrumbs, type Crumb } from "@/components/breadcrumbs";
import { truncateId } from "@/lib/format";
import { isValidUuid } from "@/lib/validation";

// TransactionResponse still has no status-history field - status is a single current value with
// no audit-trail endpoint behind it - so nothing here invents one. The double-entry rows below
// are real: they come from ledger-service via GET /transactions/{id}/ledger-entries.
function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="flex items-center justify-between border-b border-outline-variant py-2.5 last:border-b-0">
      <span className="text-xs text-text-secondary">{label}</span>
      <span className="text-sm text-text-primary">{children}</span>
    </div>
  );
}

export default function TransactionDetailPage({ params, searchParams }: PageProps<"/transactions/[id]">) {
  const { id } = use(params);
  // Client component pages read searchParams through use(), same as params - see Next's
  // app/api-reference/file-conventions/page docs. Avoids useSearchParams and its Suspense boundary.
  const { from } = use(searchParams);
  const { data: tx, error, loading, refetch } = useAuthorizedResource<TransactionResponse>(`/transactions/${id}`);
  const ledgerEntries = useAuthorizedResource<LedgerEntryResponse[]>(
    `/transactions/${id}/ledger-entries`,
  );

  // Prefer the wallet the user actually navigated from; a transfer has two legs and they may own
  // only one, so the transaction alone cannot say which is "theirs". Validated before it becomes an
  // href so a hand-edited query string can't put arbitrary text in the trail. Falling back to the
  // from-leg is a guess, but the only one available for a directly-pasted URL.
  const originWallet = typeof from === "string" && isValidUuid(from) ? from : undefined;
  const parentWallet = originWallet ?? tx?.fromWalletId ?? tx?.toWalletId ?? undefined;

  const crumbs: Crumb[] = [{ label: "Accounts", href: "/" }];
  if (parentWallet) {
    crumbs.push({
      label: truncateId(parentWallet),
      href: `/wallets/${parentWallet}`,
      title: parentWallet,
      mono: true,
    });
  }
  crumbs.push({ label: "Transaction" });

  return (
    <div className="mx-auto w-full max-w-2xl px-6 py-8">
      <Breadcrumbs items={crumbs} />
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

      {tx ? (
        <div className="mt-6">
          <h2 className="mb-3 text-sm font-medium text-text-primary">Ledger entries</h2>
          {ledgerEntries.loading ? (
            <Skeleton className="h-24 w-full" />
          ) : ledgerEntries.error ? (
            <ErrorBanner error={ledgerEntries.error} />
          ) : ledgerEntries.data && ledgerEntries.data.length > 0 ? (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Direction</TableHead>
                  <TableHead>Wallet</TableHead>
                  <TableHead className="text-right">Amount</TableHead>
                  <TableHead>Posted</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {ledgerEntries.data.map((entry) => (
                  <TableRow key={entry.id}>
                    <TableCell>{entry.direction}</TableCell>
                    <TableCell className="font-mono text-xs">{entry.walletId}</TableCell>
                    <TableCell className="text-right">
                      <AmountDisplay value={entry.amount} currency={entry.currency} size="sm" />
                    </TableCell>
                    <TableCell className="font-mono text-xs text-text-secondary">
                      {entry.createdAt}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          ) : (
            // Expected while a transaction is still PENDING: entries only exist once
            // ledger-service has posted them, which happens asynchronously via the saga.
            <EmptyState
              title="No ledger entries yet"
              description="Entries are written once ledger-service posts this transaction."
            />
          )}
        </div>
      ) : null}
    </div>
  );
}
