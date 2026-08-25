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
}

const sizeClasses = {
  sm: "text-sm",
  md: "text-base",
  lg: "text-2xl",
};

export function AmountDisplay({ value, currency, sign, size = "md", className }: AmountDisplayProps) {
  const prefix = sign === "debit" ? "-" : sign === "credit" ? "+" : "";
  return (
    <span
      className={cn(
        "font-mono tabular-nums text-right whitespace-nowrap",
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
