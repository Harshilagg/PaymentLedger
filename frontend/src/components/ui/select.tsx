import { type SelectHTMLAttributes, forwardRef } from "react";
import { cn } from "@/lib/cn";

interface SelectProps extends SelectHTMLAttributes<HTMLSelectElement> {
  error?: boolean;
}

export const Select = forwardRef<HTMLSelectElement, SelectProps>(
  ({ className, error, children, ...props }, ref) => {
    return (
      <select
        ref={ref}
        className={cn(
          "w-full appearance-none rounded-(--radius-sm) border bg-surface-container-lowest px-3 py-2 text-sm text-text-primary",
          "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-accent",
          "disabled:opacity-50 disabled:pointer-events-none",
          error ? "border-error" : "border-outline-variant",
          className,
        )}
        aria-invalid={error || undefined}
        {...props}
      >
        {children}
      </select>
    );
  },
);
Select.displayName = "Select";
