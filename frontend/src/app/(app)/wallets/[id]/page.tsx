"use client";

import { use, useState } from "react";
import { useRouter } from "next/navigation";
import { useAuthorizedResource } from "@/lib/use-authorized-resource";
import { TRANSACTIONS_PAGE_SIZE } from "@/lib/api";
import type { PageResponse, TransactionResponse, WalletResponse } from "@/lib/types";
import { amountForWallet } from "@/lib/transaction-view";
import { Table, TableHeader, TableBody, TableRow, TableHead, TableCell } from "@/components/ui/table";
import { WalletStatusBadge, TransactionStatusBadge } from "@/components/ui/badge";
import { AmountDisplay } from "@/components/ui/amount";
import { Card, CardContent } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import { EmptyState } from "@/components/ui/empty-state";
import { ErrorBanner } from "@/components/error-banner";
import { NewTransactionSheet } from "@/components/new-transaction-sheet";
import { Button } from "@/components/ui/button";

export default function WalletDetailPage({ params }: PageProps<"/wallets/[id]">) {
  const { id } = use(params);
  const router = useRouter();

  const [page, setPage] = useState(0);

  const wallet = useAuthorizedResource<WalletResponse>(`/wallets/${id}`);
  // The page number is part of the request path, so useAuthorizedResource treats each page as its
  // own resource and handles the reset-to-loading transition between them without extra state.
  const transactions = useAuthorizedResource<PageResponse<TransactionResponse>>(
    `/wallets/${id}/transactions?page=${page}&size=${TRANSACTIONS_PAGE_SIZE}`,
  );

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
            <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
              <div className="flex min-w-0 flex-col items-end gap-1">
                <span className="text-xs text-text-secondary">Balance</span>
                <AmountDisplay value={wallet.data.balance} currency={wallet.data.currency} size="lg" wrap />
              </div>
              <div className="flex min-w-0 flex-col items-end gap-1">
                <span className="text-xs text-text-secondary">Reserved</span>
                <AmountDisplay value={wallet.data.reserved} currency={wallet.data.currency} size="lg" wrap />
              </div>
              <div className="flex min-w-0 flex-col items-end gap-1">
                <span className="text-xs text-text-secondary">Available</span>
                <AmountDisplay value={wallet.data.available} currency={wallet.data.currency} size="lg" wrap />
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
      ) : transactions.data && transactions.data.content.length > 0 ? (
        <>
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
            {transactions.data.content.map((tx) => {
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
        {transactions.data.totalPages > 1 ? (
          <div className="mt-3 flex items-center justify-between">
            <span className="text-xs text-text-secondary">
              Page {transactions.data.number + 1} of {transactions.data.totalPages}
              {" · "}
              {transactions.data.totalElements} transactions
            </span>
            <div className="flex items-center gap-2">
              <Button
                variant="secondary"
                disabled={transactions.data.first}
                onClick={() => setPage((current) => Math.max(0, current - 1))}
              >
                Previous
              </Button>
              <Button
                variant="secondary"
                disabled={transactions.data.last}
                onClick={() => setPage((current) => current + 1)}
              >
                Next
              </Button>
            </div>
          </div>
        ) : null}
        </>
      ) : page > 0 ? (
        // Only reachable if rows were removed while paging; recover rather than dead-ending.
        <EmptyState
          title="No transactions on this page"
          description="Go back to see the earlier pages of this wallet's history."
          action={<Button variant="secondary" onClick={() => setPage(0)}>Back to first page</Button>}
        />
      ) : (
        <EmptyState title="No transactions yet" description="Deposits, withdrawals, and transfers on this wallet will appear here." />
      )}
    </div>
  );
}
