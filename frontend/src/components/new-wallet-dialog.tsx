"use client";

import { useState, type FormEvent } from "react";
import { useAuth } from "@/lib/auth";
import { createWallet, ApiError } from "@/lib/api";
import { isValidCurrencyCode } from "@/lib/validation";
import { useToast } from "@/components/ui/toast";
import { Dialog, DialogTrigger, DialogContent, DialogClose } from "@/components/ui/dialog";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";

export function NewWalletDialog({ accountId, onCreated }: { accountId: string; onCreated: () => void }) {
  const { token } = useAuth();
  const toast = useToast();
  const [open, setOpen] = useState(false);
  const [currency, setCurrency] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    setError(null);

    const normalized = currency.trim().toUpperCase();
    if (!isValidCurrencyCode(normalized)) {
      setError("Currency must be exactly 3 uppercase letters (e.g. USD).");
      return;
    }

    if (!token) return;
    setSubmitting(true);
    try {
      await createWallet(accountId, normalized, token);
      onCreated();
      toast.publish({ title: `${normalized} wallet created` });
      setOpen(false);
      setCurrency("");
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Could not create wallet. Try again.");
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <Dialog
      open={open}
      onOpenChange={(next) => {
        setOpen(next);
        if (!next) {
          setError(null);
          setCurrency("");
        }
      }}
    >
      <DialogTrigger asChild>
        <Button variant="primary">New Wallet</Button>
      </DialogTrigger>
      <DialogContent title="New Wallet" description="Create a wallet for this account in a given currency.">
        <form className="flex flex-col gap-3" onSubmit={handleSubmit}>
          <div>
            <label htmlFor="currency" className="mb-1 block text-xs font-medium text-text-secondary">
              Currency
            </label>
            <Input
              id="currency"
              value={currency}
              onChange={(e) => setCurrency(e.target.value.toUpperCase())}
              placeholder="USD"
              maxLength={3}
              className="font-mono uppercase"
              error={Boolean(error)}
              autoComplete="off"
              spellCheck={false}
            />
            {error ? <p className="mt-1 text-xs text-error">{error}</p> : null}
          </div>

          <div className="flex items-center gap-2">
            <Button type="submit" variant="primary" disabled={submitting} className="flex-1">
              Create Wallet
            </Button>
            <DialogClose asChild>
              <Button type="button" variant="secondary" disabled={submitting}>
                Cancel
              </Button>
            </DialogClose>
          </div>
        </form>
      </DialogContent>
    </Dialog>
  );
}
