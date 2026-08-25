"use client";

import { useState, type FormEvent } from "react";
import { useRouter } from "next/navigation";
import { useAuth } from "@/lib/auth";
import { initiateDeposit, initiateWithdrawal, initiateTransfer, ApiError } from "@/lib/api";
import { isPositiveDecimal, isValidUuid } from "@/lib/validation";
import type { TransactionType } from "@/lib/types";
import { useToast } from "@/components/ui/toast";
import { Sheet, SheetTrigger, SheetContent, SheetClose } from "@/components/ui/sheet";
import { Button } from "@/components/ui/button";
import { AmountInput, Input } from "@/components/ui/input";
import { cn } from "@/lib/cn";

type WriteType = Extract<TransactionType, "DEPOSIT" | "WITHDRAWAL" | "TRANSFER">;
const TYPES: WriteType[] = ["DEPOSIT", "WITHDRAWAL", "TRANSFER"];

export function NewTransactionSheet({ walletId, currency }: { walletId: string; currency: string }) {
  const { token } = useAuth();
  const router = useRouter();
  const toast = useToast();

  const [open, setOpen] = useState(false);
  const [type, setType] = useState<WriteType>("DEPOSIT");
  const [amount, setAmount] = useState("");
  const [toWalletId, setToWalletId] = useState("");
  const [errors, setErrors] = useState<{ amount?: string; toWalletId?: string }>({});
  const [submitting, setSubmitting] = useState(false);
  // One key per sheet-open (one logical attempt): reused across retries of the same submission
  // so a transient failure can't double-submit, per the backend's idempotency contract. Editing
  // the fields and resubmitting under the same key is a real 409 (IdempotencyConflictException)
  // surfaced from the server, not silently swallowed - see wallet-service's IdempotencyService.
  const [idempotencyKey, setIdempotencyKey] = useState(() => crypto.randomUUID());

  function reset() {
    setType("DEPOSIT");
    setAmount("");
    setToWalletId("");
    setErrors({});
    setIdempotencyKey(crypto.randomUUID());
  }

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    if (!token) return;

    const nextErrors: typeof errors = {};
    if (!isPositiveDecimal(amount)) {
      nextErrors.amount = "Amount must be greater than zero.";
    }
    if (type === "TRANSFER" && !isValidUuid(toWalletId)) {
      nextErrors.toWalletId = "Destination wallet ID must be a valid UUID.";
    }
    setErrors(nextErrors);
    if (Object.keys(nextErrors).length > 0) return;

    setSubmitting(true);
    try {
      const response =
        type === "DEPOSIT"
          ? await initiateDeposit(walletId, amount, idempotencyKey, token)
          : type === "WITHDRAWAL"
            ? await initiateWithdrawal(walletId, amount, idempotencyKey, token)
            : await initiateTransfer(walletId, toWalletId.trim(), amount, idempotencyKey, token);

      setOpen(false);
      reset();
      toast.publish({ title: "Transaction submitted", description: `Status: ${response.status}` });
      router.push(`/transactions/${response.transactionId}`);
    } catch (err) {
      toast.publish({
        title: "Transaction failed",
        description: err instanceof ApiError ? err.message : "Try again.",
        tone: "error",
      });
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <Sheet
      open={open}
      onOpenChange={(next) => {
        setOpen(next);
        if (!next) reset();
      }}
    >
      <SheetTrigger asChild>
        <Button variant="primary">New Transaction</Button>
      </SheetTrigger>
      <SheetContent title="New Transaction" description={`On wallet ${walletId}`}>
        <form className="flex flex-col gap-4" onSubmit={handleSubmit}>
          <div className="flex rounded-(--radius-sm) border border-outline-variant p-0.5">
            {TYPES.map((option) => (
              <button
                key={option}
                type="button"
                onClick={() => setType(option)}
                className={cn(
                  "flex-1 rounded-(--radius-sm) py-1.5 text-xs font-medium transition-colors",
                  type === option
                    ? "bg-accent text-accent-foreground"
                    : "text-text-secondary hover:bg-surface-container-low",
                )}
              >
                {option}
              </button>
            ))}
          </div>

          <div>
            <label htmlFor="amount" className="mb-1 block text-xs font-medium text-text-secondary">
              Amount
            </label>
            <AmountInput
              id="amount"
              currency={currency}
              value={amount}
              onChange={(e) => setAmount(e.target.value)}
              placeholder="0.00"
              error={Boolean(errors.amount)}
              autoComplete="off"
            />
            {errors.amount ? <p className="mt-1 text-xs text-error">{errors.amount}</p> : null}
          </div>

          {type === "TRANSFER" ? (
            <div>
              <label htmlFor="toWalletId" className="mb-1 block text-xs font-medium text-text-secondary">
                Destination Wallet ID
              </label>
              <Input
                id="toWalletId"
                value={toWalletId}
                onChange={(e) => setToWalletId(e.target.value)}
                placeholder="00000000-0000-0000-0000-000000000000"
                className="font-mono"
                error={Boolean(errors.toWalletId)}
                autoComplete="off"
                spellCheck={false}
              />
              {errors.toWalletId ? <p className="mt-1 text-xs text-error">{errors.toWalletId}</p> : null}
            </div>
          ) : null}

          <div className="flex items-center gap-2">
            <Button type="submit" variant="primary" disabled={submitting} className="flex-1">
              Submit Transaction
            </Button>
            <SheetClose asChild>
              <Button type="button" variant="secondary" disabled={submitting}>
                Cancel
              </Button>
            </SheetClose>
          </div>
        </form>
      </SheetContent>
    </Sheet>
  );
}
