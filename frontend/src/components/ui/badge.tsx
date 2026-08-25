import { type HTMLAttributes } from "react";
import { cn } from "@/lib/cn";
import type { TransactionStatus, WalletStatus } from "@/lib/types";

export function Badge({ className, ...props }: HTMLAttributes<HTMLSpanElement>) {
  return (
    <span
      className={cn(
        "inline-flex items-center rounded-(--radius-sm) border px-2 py-0.5 text-xs font-medium",
        className,
      )}
      {...props}
    />
  );
}

const transactionStatusTone: Record<TransactionStatus, string> = {
  PENDING: "bg-status-pending-bg text-status-pending-text border-status-pending-text/20",
  COMPLETED: "bg-status-completed-bg text-status-completed-text border-status-completed-text/20",
  COMPENSATING:
    "bg-status-compensating-bg text-status-compensating-text border-status-compensating-text/20",
  FAILED: "bg-status-failed-bg text-status-failed-text border-status-failed-text/20",
};

export function TransactionStatusBadge({ status }: { status: TransactionStatus }) {
  return <Badge className={transactionStatusTone[status]}>{status}</Badge>;
}

const walletStatusTone: Record<WalletStatus, string> = {
  ACTIVE: "bg-status-active-bg text-status-active-text border-status-active-text/20",
  FROZEN: "bg-status-frozen-bg text-status-frozen-text border-status-frozen-text/20",
  CLOSED: "bg-status-closed-bg text-status-closed-text border-status-closed-text/20",
};

export function WalletStatusBadge({ status }: { status: WalletStatus }) {
  return <Badge className={walletStatusTone[status]}>{status}</Badge>;
}
