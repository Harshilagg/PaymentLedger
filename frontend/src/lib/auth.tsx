"use client";

import { createContext, useContext, useEffect, useMemo, useSyncExternalStore, type ReactNode } from "react";
import { login as loginRequest, register as registerRequest, registerAuthBridge, type AuthTokens } from "@/lib/api";

// wallet-service authenticates with an email/password pair and returns a token pair (see
// AuthController): a 15-minute access token sent as a bearer header, and a 14-day refresh token
// that is rotated on every use. userId comes back in the response, so nothing here parses the JWT.
const STORAGE_KEY = "payment-ledger.auth";

// Module-level cache so getSnapshot returns a stable reference when localStorage hasn't actually
// changed - useSyncExternalStore re-renders whenever the snapshot reference changes, so a naive
// "JSON.parse every call" implementation would loop forever.
let cachedRaw: string | null = null;
let cachedAuth: AuthTokens | null = null;

function readStoredAuth(): AuthTokens | null {
  const raw = window.localStorage.getItem(STORAGE_KEY);
  if (raw === cachedRaw) return cachedAuth;
  cachedRaw = raw;
  try {
    cachedAuth = raw ? (JSON.parse(raw) as AuthTokens) : null;
  } catch {
    cachedAuth = null;
  }
  return cachedAuth;
}

function getServerAuthSnapshot(): AuthTokens | null {
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

function writeStoredAuth(auth: AuthTokens | null) {
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
  accessToken: string | null;
  userId: string | null;
  /** True once it's safe to trust `accessToken`/`userId` (i.e. localStorage has been read). */
  ready: boolean;
  register: (email: string, password: string) => Promise<void>;
  login: (email: string, password: string) => Promise<void>;
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

  // Lets apiFetch refresh an expired access token and replay the request without every caller
  // having to know about it. Reads through readStoredAuth rather than closing over `stored` so a
  // refresh triggered mid-render always spends the current token, not a stale render's copy.
  useEffect(
    () =>
      registerAuthBridge({
        getTokens: () => readStoredAuth(),
        onRefreshed: (tokens) => writeStoredAuth(tokens),
        onRefreshFailed: () => writeStoredAuth(null),
      }),
    [],
  );

  const value = useMemo<AuthContextValue>(
    () => ({
      accessToken: stored?.accessToken ?? null,
      userId: stored?.userId ?? null,
      ready,
      register: async (email: string, password: string) => {
        writeStoredAuth(await registerRequest(email, password));
      },
      login: async (email: string, password: string) => {
        writeStoredAuth(await loginRequest(email, password));
      },
      logout: () => writeStoredAuth(null),
    }),
    [stored, ready],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}
