"use client";

import { useEffect, useState } from "react";
import { apiFetch, ApiError } from "@/lib/api";
import { useAuth } from "@/lib/auth";

interface ResourceState<T> {
  data: T | null;
  error: ApiError | Error | null;
  loading: boolean;
}

interface Resource<T> extends ResourceState<T> {
  /** Re-runs the fetch, e.g. after a write action changes the underlying resource. */
  refetch: () => void;
}

const IDLE: ResourceState<never> = { data: null, error: null, loading: false };

/**
 * Fetches a GET endpoint scoped to the signed-in owner's token. Keyed by the request path
 * (rather than an arbitrary fetcher callback) so the effect's dependency array stays exact.
 */
export function useAuthorizedResource<T>(path: string | null): Resource<T> {
  const { token, ready } = useAuth();
  const canFetch = ready && Boolean(token) && Boolean(path);

  const [refetchNonce, setRefetchNonce] = useState(0);
  // The nonce is folded into the key itself, so bumping it on refetch() reuses the exact same
  // "key changed -> reset to loading, effect re-runs" path as a normal path change below.
  const effectiveKey = canFetch ? `${path}::${refetchNonce}` : null;

  const [requestKey, setRequestKey] = useState<string | null>(null);
  const [asyncState, setAsyncState] = useState<ResourceState<T>>(IDLE);

  // Resets state synchronously during render when the target key changes, rather than in the
  // effect below - the React-docs pattern for "adjusting state when a prop changes" - so the
  // effect itself only ever calls setState from its fetch callbacks, never in its own body.
  if (effectiveKey !== requestKey) {
    setRequestKey(effectiveKey);
    setAsyncState(effectiveKey ? { data: null, error: null, loading: true } : IDLE);
  }

  useEffect(() => {
    if (!canFetch || !token || !path) return;

    let cancelled = false;
    apiFetch<T>(path, { token })
      .then((data) => {
        if (!cancelled) setAsyncState({ data, error: null, loading: false });
      })
      .catch((error: unknown) => {
        if (!cancelled) {
          setAsyncState({
            data: null,
            error: error instanceof Error ? error : new Error(String(error)),
            loading: false,
          });
        }
      });

    return () => {
      cancelled = true;
    };
  }, [canFetch, token, path, refetchNonce]);

  return { ...asyncState, refetch: () => setRefetchNonce((n) => n + 1) };
}
