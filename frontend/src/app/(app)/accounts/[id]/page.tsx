"use client";

import { use } from "react";
import { useRouter } from "next/navigation";
import { useAuthorizedResource } from "@/lib/use-authorized-resource";
import type { AccountResponse, WalletResponse } from "@/lib/types";
import { Table, TableHeader, TableBody, TableRow, TableHead, TableCell } from "@/components/ui/table";
import { WalletStatusBadge } from "@/components/ui/badge";
import { AmountDisplay } from "@/components/ui/amount";
import { Skeleton } from "@/components/ui/skeleton";
import { EmptyState } from "@/components/ui/empty-state";
import { ErrorBanner } from "@/components/error-banner";

export default function AccountDetailPage({ params }: PageProps<"/accounts/[id]">) {
  const { id } = use(params);
  const router = useRouter();

  const account = useAuthorizedResource<AccountResponse>(`/accounts/${id}`);
  const wallets = useAuthorizedResource<WalletResponse[]>(`/accounts/${id}/wallets`);

  return (
    <div className="mx-auto w-full max-w-3xl px-6 py-8">
      {account.loading ? (
        <Skeleton className="h-6 w-64" />
      ) : account.error ? (
        <ErrorBanner error={account.error} />
      ) : account.data ? (
        <div className="mb-6">
          <h1 className="font-mono text-sm text-text-primary">{account.data.id}</h1>
          <p className="mt-1 text-xs text-text-secondary">Created {account.data.createdAt}</p>
        </div>
      ) : null}

      <h2 className="mb-3 text-sm font-medium text-text-primary">Wallets</h2>

      {wallets.loading ? (
        <div className="flex flex-col gap-2">
          <Skeleton className="h-9 w-full" />
          <Skeleton className="h-9 w-full" />
        </div>
      ) : wallets.error ? (
        <ErrorBanner error={wallets.error} />
      ) : wallets.data && wallets.data.length > 0 ? (
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Wallet ID</TableHead>
              <TableHead>Currency</TableHead>
              <TableHead>Status</TableHead>
              <TableHead className="text-right">Balance</TableHead>
              <TableHead className="text-right">Reserved</TableHead>
              <TableHead className="text-right">Available</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {wallets.data.map((wallet) => (
              <TableRow
                key={wallet.id}
                clickable
                role="link"
                tabIndex={0}
                onClick={() => router.push(`/wallets/${wallet.id}`)}
                onKeyDown={(e) => {
                  if (e.key === "Enter") router.push(`/wallets/${wallet.id}`);
                }}
              >
                <TableCell className="font-mono text-xs">{wallet.id}</TableCell>
                <TableCell>{wallet.currency}</TableCell>
                <TableCell>
                  <WalletStatusBadge status={wallet.status} />
                </TableCell>
                <TableCell className="text-right">
                  <AmountDisplay value={wallet.balance} currency={wallet.currency} size="sm" />
                </TableCell>
                <TableCell className="text-right">
                  <AmountDisplay value={wallet.reserved} currency={wallet.currency} size="sm" />
                </TableCell>
                <TableCell className="text-right">
                  <AmountDisplay value={wallet.available} currency={wallet.currency} size="sm" />
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      ) : (
        <EmptyState title="No wallets in this account" />
      )}
    </div>
  );
}
