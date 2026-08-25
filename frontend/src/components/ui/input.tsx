import { type InputHTMLAttributes, forwardRef } from "react";
import { cn } from "@/lib/cn";

interface InputProps extends InputHTMLAttributes<HTMLInputElement> {
  error?: boolean;
}

export const Input = forwardRef<HTMLInputElement, InputProps>(
  ({ className, error, ...props }, ref) => {
    return (
      <input
        ref={ref}
        className={cn(
          "w-full rounded-(--radius-sm) border bg-surface-container-lowest px-3 py-2 text-sm text-text-primary",
          "placeholder:text-text-secondary",
          "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-accent",
          "disabled:opacity-50 disabled:pointer-events-none",
          error ? "border-error" : "border-outline-variant",
          className,
        )}
        aria-invalid={error || undefined}
        {...props}
      />
    );
  },
);
Input.displayName = "Input";

interface AmountInputProps extends Omit<InputProps, "type" | "inputMode"> {
  currency: string;
}

export const AmountInput = forwardRef<HTMLInputElement, AmountInputProps>(
  ({ className, currency, error, ...props }, ref) => {
    return (
      <div className="relative">
        <input
          ref={ref}
          type="text"
          inputMode="decimal"
          className={cn(
            "w-full rounded-(--radius-sm) border bg-surface-container-lowest py-2 pl-3 pr-16 text-right font-mono text-lg text-text-primary",
            "placeholder:text-text-secondary",
            "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-accent",
            "disabled:opacity-50 disabled:pointer-events-none",
            error ? "border-error" : "border-outline-variant",
            className,
          )}
          aria-invalid={error || undefined}
          {...props}
        />
        <span className="pointer-events-none absolute right-3 top-1/2 -translate-y-1/2 font-mono text-sm text-text-secondary">
          {currency}
        </span>
      </div>
    );
  },
);
AmountInput.displayName = "AmountInput";
