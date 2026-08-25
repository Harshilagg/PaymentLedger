"use client";

import { use } from "react";
import { useRouter } from "next/navigation";
import { useAuthorizedResource } from "@/lib/use-authorized-resource";
import type { TransactionResponse, WalletResponse } from "@/lib/types";
import { amountForWallet } from "@/lib/transaction-view";
import { Table, TableHeader, TableBody, TableRow, TableHead, TableCell } from "@/components/ui/table";
import { WalletStatusBadge, TransactionStatusBadge } from "@/components/ui/badge";
import { AmountDisplay } from "@/components/ui/amount";
import { Card, CardContent } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import { EmptyState } from "@/components/ui/empty-state";
import { ErrorBanner } from "@/components/error-banner";
import { NewTransactionSheet } from "@/components/new-transaction-sheet";

export default function WalletDetailPage({ params }: PageProps<"/wallets/[id]">) {
  const { id } = use(params);
  const router = useRouter();

  const wallet = useAuthorizedResource<WalletResponse>(`/wallets/${id}`);
  const transactions = useAuthorizedResource<TransactionResponse[]>(`/wallets/${id}/transactions`);

  return (
    <div className="mx-auto w-full max-w-3xl px-6 py-8">
      {wallet.loading ? (
        <Skeleton className="h-24 w-full" />
      ) : wallet.error ? (
        <ErrorBanner error={wallet.error} />
      ) : wallet.data ? (
        <Card className="mb-6">
          <CardContent className="flex flex-col gap-4">
            <div className="flex items-center justify-between">
              <span className="font-mono text-xs text-text-secondary">{wallet.data.id}</span>
              <div className="flex items-center gap-2">
                <span className="text-xs text-text-secondary">{wallet.data.currency}</span>
                <WalletStatusBadge status={wallet.data.status} />
              </div>
            </div>
            <div className="grid grid-cols-3 gap-4">
              <div className="flex flex-col items-end gap-1">
                <span className="text-xs text-text-secondary">Balance</span>
                <AmountDisplay value={wallet.data.balance} currency={wallet.data.currency} size="lg" />
              </div>
              <div className="flex flex-col items-end gap-1">
                <span className="text-xs text-text-secondary">Reserved</span>
                <AmountDisplay value={wallet.data.reserved} currency={wallet.data.currency} size="lg" />
              </div>
              <div className="flex flex-col items-end gap-1">
                <span className="text-xs text-text-secondary">Available</span>
                <AmountDisplay value={wallet.data.available} currency={wallet.data.currency} size="lg" />
              </div>
            </div>
          </CardContent>
        </Card>
      ) : null}

      <div className="mb-3 flex items-center justify-between">
        <h2 className="text-sm font-medium text-text-primary">Transactions</h2>
        {wallet.data ? <NewTransactionSheet walletId={id} currency={wallet.data.currency} /> : null}
      </div>

      {transactions.loading ? (
        <div className="flex flex-col gap-2">
          <Skeleton className="h-9 w-full" />
          <Skeleton className="h-9 w-full" />
          <Skeleton className="h-9 w-full" />
        </div>
      ) : transactions.error ? (
        <ErrorBanner error={transactions.error} />
      ) : transactions.data && transactions.data.length > 0 ? (
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Transaction ID</TableHead>
              <TableHead>Type</TableHead>
              <TableHead>Status</TableHead>
              <TableHead className="text-right">Amount</TableHead>
              <TableHead>Created</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {transactions.data.map((tx) => {
              const amount = amountForWallet(tx, id);
              return (
                <TableRow
                  key={tx.transactionId}
                  clickable
                  role="link"
                  tabIndex={0}
                  onClick={() => router.push(`/transactions/${tx.transactionId}`)}
                  onKeyDown={(e) => {
                    if (e.key === "Enter") router.push(`/transactions/${tx.transactionId}`);
                  }}
                >
                  <TableCell className="font-mono text-xs">{tx.transactionId}</TableCell>
                  <TableCell>{tx.type}</TableCell>
                  <TableCell>
                    <TransactionStatusBadge status={tx.status} />
                  </TableCell>
                  <TableCell className="text-right">
                    {amount ? (
                      <AmountDisplay value={amount.value} currency={amount.currency} sign={amount.sign} size="sm" />
                    ) : null}
                  </TableCell>
                  <TableCell className="text-text-secondary">{tx.createdAt}</TableCell>
                </TableRow>
              );
            })}
          </TableBody>
        </Table>
      ) : (
        <EmptyState title="No transactions yet" description="Deposits, withdrawals, and transfers on this wallet will appear here." />
      )}
    </div>
  );
}
