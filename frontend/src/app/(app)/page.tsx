"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { useAuthorizedResource } from "@/lib/use-authorized-resource";
import { useAuth } from "@/lib/auth";
import { createAccount, ApiError } from "@/lib/api";
import type { AccountResponse } from "@/lib/types";
import { Table, TableHeader, TableBody, TableRow, TableHead, TableCell } from "@/components/ui/table";
import { Skeleton } from "@/components/ui/skeleton";
import { EmptyState } from "@/components/ui/empty-state";
import { ErrorBanner } from "@/components/error-banner";
import { Button } from "@/components/ui/button";
import { useToast } from "@/components/ui/toast";

export default function AccountsPage() {
  const router = useRouter();
  const { accessToken: token } = useAuth();
  const toast = useToast();
  const [creating, setCreating] = useState(false);
  const { data: accounts, error, loading, refetch } = useAuthorizedResource<AccountResponse[]>("/accounts");

  async function handleCreateAccount() {
    if (!token) return;
    setCreating(true);
    try {
      await createAccount(token);
      refetch();
      toast.publish({ title: "Account created" });
    } catch (err) {
      toast.publish({
        title: "Could not create account",
        description: err instanceof ApiError ? err.message : "Try again.",
        tone: "error",
      });
    } finally {
      setCreating(false);
    }
  }

  return (
    <div className="mx-auto w-full max-w-3xl px-6 py-8">
      <div className="mb-4 flex items-center justify-between">
        <h1 className="text-sm font-medium text-text-primary">Accounts</h1>
        <Button variant="primary" onClick={handleCreateAccount} disabled={creating}>
          New Account
        </Button>
      </div>

      {loading ? (
        <div className="flex flex-col gap-2">
          <Skeleton className="h-9 w-full" />
          <Skeleton className="h-9 w-full" />
          <Skeleton className="h-9 w-full" />
        </div>
      ) : error ? (
        <ErrorBanner error={error} />
      ) : accounts && accounts.length > 0 ? (
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Account ID</TableHead>
              <TableHead>Created</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {accounts.map((account) => (
              <TableRow
                key={account.id}
                clickable
                role="link"
                tabIndex={0}
                onClick={() => router.push(`/accounts/${account.id}`)}
                onKeyDown={(e) => {
                  if (e.key === "Enter") router.push(`/accounts/${account.id}`);
                }}
              >
                <TableCell className="font-mono text-xs">{account.id}</TableCell>
                <TableCell className="text-text-secondary">{account.createdAt}</TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      ) : (
        <EmptyState title="No accounts yet" description="Accounts created under this owner will appear here." />
      )}
    </div>
  );
}
