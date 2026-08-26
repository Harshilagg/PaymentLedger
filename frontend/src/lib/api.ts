import type { AccountResponse, TransactionResponse, WalletResponse } from "@/lib/types";

// Money fields are BigDecimal on the backend and Jackson serializes them as bare JSON numbers,
// which JSON.parse would silently round-trip through a float64. Quoting them in the raw response
// text before parsing keeps the exact digit string the backend sent (see lib/types.ts).
const MONEY_FIELDS = ["balance", "reserved", "available", "amount", "toAmount"];
const MONEY_FIELD_PATTERN = new RegExp(
  `"(${MONEY_FIELDS.join("|")})":(-?\\d+(?:\\.\\d+)?)`,
  "g",
);

function parsePreservingMoneyPrecision(rawJson: string): unknown {
  return JSON.parse(rawJson.replace(MONEY_FIELD_PATTERN, '"$1":"$2"'));
}

export class ApiError extends Error {
  readonly status: number;
  /** Only set for the specific cases GlobalExceptionHandler customizes. */
  readonly serverMessage?: string;

  constructor(status: number, reasonPhrase: string, serverMessage?: string) {
    super(serverMessage ?? reasonPhrase);
    this.status = status;
    this.serverMessage = serverMessage;
  }
}

export interface AuthTokens {
  accessToken: string;
  refreshToken: string;
  userId: string;
}

// apiFetch is a plain function, not a hook, so it can't read the auth context to refresh an
// expired access token. AuthProvider registers itself here on mount instead, which keeps the
// retry in one place rather than duplicated across every call site below.
interface AuthBridge {
  getTokens: () => AuthTokens | null;
  onRefreshed: (tokens: AuthTokens) => void;
  onRefreshFailed: () => void;
}

let authBridge: AuthBridge | null = null;

export function registerAuthBridge(bridge: AuthBridge) {
  authBridge = bridge;
  return () => {
    if (authBridge === bridge) authBridge = null;
  };
}

// A page usually fires several requests at once, so an expired access token produces a burst of
// simultaneous 401s. Without this, each one would spend the refresh token separately and all but
// the first would look like a replay - which the backend correctly treats as theft and responds
// to by revoking the whole family, logging the user out mid-session.
let refreshInFlight: Promise<AuthTokens | null> | null = null;

function refreshTokens(): Promise<AuthTokens | null> {
  if (refreshInFlight) return refreshInFlight;

  const current = authBridge?.getTokens();
  if (!current) return Promise.resolve(null);

  refreshInFlight = apiFetch<AuthTokens>("/auth/refresh", {
    method: "POST",
    body: { refreshToken: current.refreshToken },
  })
    .then((tokens) => {
      authBridge?.onRefreshed(tokens);
      return tokens;
    })
    .catch(() => {
      authBridge?.onRefreshFailed();
      return null;
    })
    .finally(() => {
      refreshInFlight = null;
    });

  return refreshInFlight;
}

/** Thrown when fetch() itself fails - offline, DNS failure, connection refused - before any
 *  HTTP response exists, as distinct from ApiError (a response the server actually sent). */
export class NetworkError extends Error {
  constructor() {
    super("Could not reach the server. Check your connection and try again.");
  }
}

interface ApiRequestOptions {
  method?: "GET" | "POST";
  body?: unknown;
  token?: string | null;
  idempotencyKey?: string;
  /** Internal: set on the replay after a refresh so a request can only ever be retried once. */
  isRetry?: boolean;
}

export async function apiFetch<T>(path: string, options: ApiRequestOptions = {}): Promise<T> {
  const headers: Record<string, string> = { "Content-Type": "application/json" };
  if (options.token) headers.Authorization = `Bearer ${options.token}`;
  if (options.idempotencyKey) headers["Idempotency-Key"] = options.idempotencyKey;

  let response: Response;
  try {
    response = await fetch(`/api${path}`, {
      method: options.method ?? "GET",
      headers,
      body: options.body !== undefined ? JSON.stringify(options.body) : undefined,
    });
  } catch {
    throw new NetworkError();
  }

  const text = await response.text();
  const data = text ? parsePreservingMoneyPrecision(text) : undefined;

  if (!response.ok) {
    // Access tokens live 15 minutes, so a 401 on an authenticated request is far more likely to
    // be normal expiry than a real credential problem. Refresh once and replay - but never for
    // /auth/* itself, where a 401 is the answer rather than something to recover from, and never
    // more than once, so a persistently-401ing endpoint can't drive an infinite loop.
    if (response.status === 401 && options.token && !options.isRetry && !path.startsWith("/auth/")) {
      const refreshed = await refreshTokens();
      if (refreshed) {
        return apiFetch<T>(path, { ...options, token: refreshed.accessToken, isRetry: true });
      }
    }

    const serverMessage =
      data && typeof data === "object" && "message" in data
        ? String((data as { message: unknown }).message)
        : undefined;
    throw new ApiError(response.status, response.statusText, serverMessage);
  }

  return data as T;
}

// Auth endpoints (see wallet-service AuthController). None takes a token: register and login
// establish one, and refresh authenticates with the refresh token in its body.
export function register(email: string, password: string) {
  return apiFetch<AuthTokens>("/auth/register", { method: "POST", body: { email, password } });
}

export function login(email: string, password: string) {
  return apiFetch<AuthTokens>("/auth/login", { method: "POST", body: { email, password } });
}

// One function per real wallet-service endpoint (see wallet-service's AccountController,
// WalletController, TransactionController) - no endpoint is invented, and none of these accept
// pagination/filter/sort params because the backend doesn't support any.
export function listAccounts(token: string) {
  return apiFetch<AccountResponse[]>("/accounts", { token });
}

export function getAccount(accountId: string, token: string) {
  return apiFetch<AccountResponse>(`/accounts/${accountId}`, { token });
}

export function listWallets(accountId: string, token: string) {
  return apiFetch<WalletResponse[]>(`/accounts/${accountId}/wallets`, { token });
}

export function getWallet(walletId: string, token: string) {
  return apiFetch<WalletResponse>(`/wallets/${walletId}`, { token });
}

export function listWalletTransactions(walletId: string, token: string) {
  return apiFetch<TransactionResponse[]>(`/wallets/${walletId}/transactions`, { token });
}

export function getTransaction(transactionId: string, token: string) {
  return apiFetch<TransactionResponse>(`/transactions/${transactionId}`, { token });
}

export function createAccount(token: string) {
  return apiFetch<AccountResponse>("/accounts", { method: "POST", token });
}

export function createWallet(accountId: string, currency: string, token: string) {
  return apiFetch<WalletResponse>(`/accounts/${accountId}/wallets`, {
    method: "POST",
    token,
    body: { currency },
  });
}

export function initiateDeposit(walletId: string, amount: string, idempotencyKey: string, token: string) {
  return apiFetch<TransactionResponse>(`/wallets/${walletId}/deposits`, {
    method: "POST",
    token,
    idempotencyKey,
    body: { amount },
  });
}

export function initiateWithdrawal(walletId: string, amount: string, idempotencyKey: string, token: string) {
  return apiFetch<TransactionResponse>(`/wallets/${walletId}/withdrawals`, {
    method: "POST",
    token,
    idempotencyKey,
    body: { amount },
  });
}

export function initiateTransfer(
  walletId: string,
  toWalletId: string,
  amount: string,
  idempotencyKey: string,
  token: string,
) {
  return apiFetch<TransactionResponse>(`/wallets/${walletId}/transfers`, {
    method: "POST",
    token,
    idempotencyKey,
    body: { toWalletId, amount },
  });
}

export function initiateReversal(transactionId: string, idempotencyKey: string, token: string) {
  return apiFetch<TransactionResponse>(`/transactions/${transactionId}/reversals`, {
    method: "POST",
    token,
    idempotencyKey,
  });
}
