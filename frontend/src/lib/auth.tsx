"use client";

import { createContext, useContext, useMemo, useSyncExternalStore, type ReactNode } from "react";
import { apiFetch } from "@/lib/api";

// wallet-service has no user/credential model - POST /auth/token exchanges any ownerId UUID for
// a JWT scoped to it (see AuthController, TokenRequest, JwtService: 60-minute expiry, subject
// claim = ownerId). There is nothing to "sign up" for; owning the UUID *is* the identity.
const STORAGE_KEY = "payment-ledger.auth";

interface StoredAuth {
  token: string;
  ownerId: string;
}

// Module-level cache so getSnapshot returns a stable reference when localStorage hasn't actually
// changed - useSyncExternalStore re-renders whenever the snapshot reference changes, so a naive
// "JSON.parse every call" implementation would loop forever.
let cachedRaw: string | null = null;
let cachedAuth: StoredAuth | null = null;

function readStoredAuth(): StoredAuth | null {
  const raw = window.localStorage.getItem(STORAGE_KEY);
  if (raw === cachedRaw) return cachedAuth;
  cachedRaw = raw;
  try {
    cachedAuth = raw ? (JSON.parse(raw) as StoredAuth) : null;
  } catch {
    cachedAuth = null;
  }
  return cachedAuth;
}

function getServerAuthSnapshot(): StoredAuth | null {
  return null;
}

const listeners = new Set<() => void>();

function subscribe(listener: () => void) {
  listeners.add(listener);
  window.addEventListener("storage", listener);
  return () => {
    listeners.delete(listener);
    window.removeEventListener("storage", listener);
  };
}

function writeStoredAuth(auth: StoredAuth | null) {
  if (auth) {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify(auth));
  } else {
    window.localStorage.removeItem(STORAGE_KEY);
  }
  listeners.forEach((listener) => listener());
}

// Pure client/server snapshot pair with no state of its own: before hydration this always
// returns false (matching the server render), then flips to true once React re-invokes it on
// the client - the idiomatic way to know "we've hydrated" without a setState-in-effect.
function useHasHydrated() {
  return useSyncExternalStore(
    () => () => {},
    () => true,
    () => false,
  );
}

interface AuthContextValue {
  token: string | null;
  ownerId: string | null;
  /** True once it's safe to trust `token`/`ownerId` (i.e. localStorage has been read). */
  ready: boolean;
  login: (ownerId: string) => Promise<void>;
  logout: () => void;
}

const AuthContext = createContext<AuthContextValue | null>(null);

export function useAuth() {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error("useAuth must be used within AuthProvider");
  return ctx;
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const stored = useSyncExternalStore(subscribe, readStoredAuth, getServerAuthSnapshot);
  const ready = useHasHydrated();

  const value = useMemo<AuthContextValue>(
    () => ({
      token: stored?.token ?? null,
      ownerId: stored?.ownerId ?? null,
      ready,
      login: async (ownerId: string) => {
        const response = await apiFetch<{ token: string }>("/auth/token", {
          method: "POST",
          body: { ownerId },
        });
        writeStoredAuth({ token: response.token, ownerId });
      },
      logout: () => writeStoredAuth(null),
    }),
    [stored, ready],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}
