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
  /** Only set for the specific 409/422/503 cases GlobalExceptionHandler customizes. */
  readonly serverMessage?: string;

  constructor(status: number, reasonPhrase: string, serverMessage?: string) {
    super(serverMessage ?? reasonPhrase);
    this.status = status;
    this.serverMessage = serverMessage;
  }
}

interface ApiRequestOptions {
  method?: "GET" | "POST";
  body?: unknown;
  token?: string | null;
  idempotencyKey?: string;
}

export async function apiFetch<T>(path: string, options: ApiRequestOptions = {}): Promise<T> {
  const headers: Record<string, string> = { "Content-Type": "application/json" };
  if (options.token) headers.Authorization = `Bearer ${options.token}`;
  if (options.idempotencyKey) headers["Idempotency-Key"] = options.idempotencyKey;

  const response = await fetch(`/api${path}`, {
    method: options.method ?? "GET",
    headers,
    body: options.body !== undefined ? JSON.stringify(options.body) : undefined,
  });

  const text = await response.text();
  const data = text ? parsePreservingMoneyPrecision(text) : undefined;

  if (!response.ok) {
    const serverMessage =
      data && typeof data === "object" && "message" in data
        ? String((data as { message: unknown }).message)
        : undefined;
    throw new ApiError(response.status, response.statusText, serverMessage);
  }

  return data as T;
}
