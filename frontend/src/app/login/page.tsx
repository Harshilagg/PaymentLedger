"use client";

import { useEffect, useState, type FormEvent } from "react";
import { useRouter } from "next/navigation";
import { useAuth } from "@/lib/auth";
import { ApiError } from "@/lib/api";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";

type Mode = "login" | "register";

// Mirrors RegisterRequest's @Size(min = 8) so the obvious case is caught before a round trip.
// Everything else is left to the server - duplicating @Email here would only risk the two
// disagreeing about what an address is.
const MIN_PASSWORD_LENGTH = 8;

export default function LoginPage() {
  const auth = useAuth();
  const router = useRouter();
  const [mode, setMode] = useState<Mode>("login");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [confirmPassword, setConfirmPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    if (auth.ready && auth.accessToken) {
      router.replace("/");
    }
  }, [auth.ready, auth.accessToken, router]);

  function switchMode(next: Mode) {
    setMode(next);
    setError(null);
    setPassword("");
    setConfirmPassword("");
  }

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    setError(null);

    if (mode === "register") {
      if (password.length < MIN_PASSWORD_LENGTH) {
        setError(`Password must be at least ${MIN_PASSWORD_LENGTH} characters.`);
        return;
      }
      if (password !== confirmPassword) {
        setError("Passwords do not match.");
        return;
      }
    }

    setSubmitting(true);
    try {
      if (mode === "register") {
        await auth.register(email.trim(), password);
      } else {
        await auth.login(email.trim(), password);
      }
      router.replace("/");
    } catch (err) {
      setError(
        err instanceof ApiError
          ? err.message
          : mode === "register"
            ? "Could not create the account. Try again."
            : "Sign-in failed. Try again.",
      );
    } finally {
      setSubmitting(false);
    }
  }

  const isRegister = mode === "register";

  return (
    <div className="flex flex-1 items-center justify-center px-4">
      <div className="w-full max-w-sm rounded-(--radius-md) border border-outline-variant bg-surface-container-lowest p-6">
        <h1 className="text-sm font-medium text-text-primary">Payment Ledger</h1>
        <p className="mt-1 text-sm text-text-secondary">
          {isRegister ? "Create an account to get started." : "Sign in to access your accounts."}
        </p>

        <div
          role="tablist"
          aria-label="Authentication mode"
          className="mt-5 flex gap-1 rounded-(--radius-sm) border border-outline-variant p-1"
        >
          {(["login", "register"] as const).map((value) => (
            <button
              key={value}
              type="button"
              role="tab"
              aria-selected={mode === value}
              onClick={() => switchMode(value)}
              disabled={submitting}
              className={`flex-1 rounded-(--radius-sm) px-3 py-1.5 text-xs font-medium transition-colors focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent disabled:opacity-50 ${
                mode === value
                  ? "bg-accent text-accent-foreground"
                  : "text-text-secondary hover:text-text-primary"
              }`}
            >
              {value === "login" ? "Sign in" : "Register"}
            </button>
          ))}
        </div>

        <form className="mt-4 flex flex-col gap-3" onSubmit={handleSubmit}>
          <div>
            <label htmlFor="email" className="mb-1 block text-xs font-medium text-text-secondary">
              Email
            </label>
            <Input
              id="email"
              type="email"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              placeholder="you@example.com"
              error={Boolean(error)}
              autoComplete="username"
              required
            />
          </div>

          <div>
            <label htmlFor="password" className="mb-1 block text-xs font-medium text-text-secondary">
              Password
            </label>
            <Input
              id="password"
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              error={Boolean(error)}
              autoComplete={isRegister ? "new-password" : "current-password"}
              required
            />
          </div>

          {isRegister ? (
            <div>
              <label
                htmlFor="confirmPassword"
                className="mb-1 block text-xs font-medium text-text-secondary"
              >
                Confirm password
              </label>
              <Input
                id="confirmPassword"
                type="password"
                value={confirmPassword}
                onChange={(e) => setConfirmPassword(e.target.value)}
                error={Boolean(error)}
                autoComplete="new-password"
                required
              />
            </div>
          ) : null}

          {error ? (
            <p role="alert" className="text-xs text-error">
              {error}
            </p>
          ) : null}

          <Button type="submit" variant="primary" disabled={submitting}>
            {isRegister ? "Create account" : "Sign in"}
          </Button>
        </form>
      </div>
    </div>
  );
}
