"use client";

import { useState } from "react";
import { useAuth } from "@/lib/auth";
import { initiateReversal, ApiError } from "@/lib/api";
import type { TransactionResponse } from "@/lib/types";
import { useToast } from "@/components/ui/toast";
import { Dialog, DialogTrigger, DialogContent, DialogClose } from "@/components/ui/dialog";
import { Button } from "@/components/ui/button";
import { AmountDisplay } from "@/components/ui/amount";

export function ReverseTransactionDialog({
  transaction,
  onReversed,
}: {
  transaction: TransactionResponse;
  onReversed: () => void;
}) {
  const { token } = useAuth();
  const toast = useToast();
  const [open, setOpen] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [idempotencyKey] = useState(() => crypto.randomUUID());

  async function handleConfirm() {
    if (!token) return;
    setSubmitting(true);
    try {
      await initiateReversal(transaction.transactionId, idempotencyKey, token);
      setOpen(false);
      onReversed();
      toast.publish({ title: "Reversal submitted" });
    } catch (err) {
      toast.publish({
        title: "Could not reverse transaction",
        description: err instanceof ApiError ? err.message : "Try again.",
        tone: "error",
      });
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogTrigger asChild>
        <Button variant="destructive">Reverse Transaction</Button>
      </DialogTrigger>
      <DialogContent
        title="Reverse this transaction?"
        description="This submits a new, separate reversal transaction against the same wallets. It cannot be undone."
      >
        <div className="mb-4 flex items-center justify-between rounded-(--radius-sm) border border-outline-variant px-3 py-2">
          <span className="font-mono text-xs text-text-secondary">{transaction.transactionId}</span>
          <AmountDisplay value={transaction.amount} currency={transaction.currency} size="sm" />
        </div>
        <div className="flex items-center gap-2">
          <Button variant="destructive" onClick={handleConfirm} disabled={submitting} className="flex-1">
            Reverse Transaction
          </Button>
          <DialogClose asChild>
            <Button type="button" variant="secondary" disabled={submitting}>
              Cancel
            </Button>
          </DialogClose>
        </div>
      </DialogContent>
    </Dialog>
  );
}
