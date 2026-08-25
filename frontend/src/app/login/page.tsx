"use client";

import { useEffect, useState, type FormEvent } from "react";
import { useRouter } from "next/navigation";
import { useAuth } from "@/lib/auth";
import { ApiError } from "@/lib/api";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";

const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export default function LoginPage() {
  const auth = useAuth();
  const router = useRouter();
  const [ownerId, setOwnerId] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    if (auth.ready && auth.token) {
      router.replace("/");
    }
  }, [auth.ready, auth.token, router]);

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    setError(null);

    if (!UUID_PATTERN.test(ownerId.trim())) {
      setError("Owner ID must be a valid UUID.");
      return;
    }

    setSubmitting(true);
    try {
      await auth.login(ownerId.trim());
      router.replace("/");
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Sign-in failed. Try again.");
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="flex flex-1 items-center justify-center px-4">
      <div className="w-full max-w-sm rounded-(--radius-md) border border-outline-variant bg-surface-container-lowest p-6">
        <h1 className="text-sm font-medium text-text-primary">Payment Ledger</h1>
        <p className="mt-1 text-sm text-text-secondary">
          Sign in with an Owner ID to access its accounts.
        </p>

        <form className="mt-5 flex flex-col gap-3" onSubmit={handleSubmit}>
          <div>
            <label htmlFor="ownerId" className="mb-1 block text-xs font-medium text-text-secondary">
              Owner ID
            </label>
            <Input
              id="ownerId"
              value={ownerId}
              onChange={(e) => setOwnerId(e.target.value)}
              placeholder="00000000-0000-0000-0000-000000000000"
              className="font-mono"
              error={Boolean(error)}
              autoComplete="off"
              spellCheck={false}
            />
            {error ? <p className="mt-1 text-xs text-error">{error}</p> : null}
          </div>

          <div className="flex items-center gap-2">
            <Button type="submit" variant="primary" disabled={submitting} className="flex-1">
              Sign in
            </Button>
            <Button
              type="button"
              variant="secondary"
              onClick={() => setOwnerId(crypto.randomUUID())}
              disabled={submitting}
            >
              Generate new ID
            </Button>
          </div>
        </form>
      </div>
    </div>
  );
}
