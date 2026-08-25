import { cn } from "@/lib/cn";
import { formatAmount } from "@/lib/format";

interface AmountDisplayProps {
  /** Decimal string as returned by the API, e.g. "1250.00". Never a pre-rounded number. */
  value: string;
  currency: string;
  /** Debit/credit shown via sign, not color alone. Omit for a plain balance figure. */
  sign?: "debit" | "credit";
  size?: "sm" | "md" | "lg";
  className?: string;
  /**
   * Table/row contexts need nowrap so columns stay aligned (the default). A handful of standalone
   * stat displays (e.g. the wallet balance header) have no neighboring column to misalign and
   * would rather wrap onto a second line than scroll a pathologically large amount out of view -
   * scrolling defaults to the left edge, which visually hides the currency and cents even though
   * the value itself is intact, and that reads as truncation even when it technically isn't.
   */
  wrap?: boolean;
}

const sizeClasses = {
  sm: "text-sm",
  md: "text-base",
  lg: "text-2xl",
};

export function AmountDisplay({ value, currency, sign, size = "md", className, wrap = false }: AmountDisplayProps) {
  const prefix = sign === "debit" ? "-" : sign === "credit" ? "+" : "";
  return (
    <span
      className={cn(
        "font-mono tabular-nums text-right",
        wrap ? "whitespace-normal break-words" : "whitespace-nowrap",
        sizeClasses[size],
        sign === "debit" && "text-status-failed-text",
        sign === "credit" && "text-status-completed-text",
        !sign && "text-text-primary",
        className,
      )}
    >
      {prefix}
      {formatAmount(value)}{" "}
      <span className="text-text-secondary">{currency}</span>
    </span>
  );
}
