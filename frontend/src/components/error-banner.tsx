import { ApiError } from "@/lib/api";

export function ErrorBanner({ error }: { error: Error }) {
  const message =
    error instanceof ApiError
      ? (error.serverMessage ?? `Request failed (${error.status} ${error.message})`)
      : error.message;

  return (
    <div className="rounded-(--radius-md) border border-status-failed-text/30 bg-status-failed-bg px-4 py-3 text-sm text-status-failed-text">
      {message}
    </div>
  );
}
