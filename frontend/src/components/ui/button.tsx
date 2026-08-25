import { type ButtonHTMLAttributes, forwardRef } from "react";
import { cn } from "@/lib/cn";

type ButtonVariant = "primary" | "secondary" | "destructive" | "ghost";

interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: ButtonVariant;
}

const variantClasses: Record<ButtonVariant, string> = {
  primary:
    "bg-accent text-accent-foreground border border-accent hover:bg-accent/90",
  secondary:
    "bg-surface-container-lowest text-text-primary border border-outline-variant hover:bg-surface-container-low",
  destructive:
    "bg-status-failed-bg text-status-failed-text border border-status-failed-text/30 hover:bg-status-failed-bg/80",
  ghost:
    "bg-transparent text-text-secondary border border-transparent hover:bg-surface-container-low",
};

export const Button = forwardRef<HTMLButtonElement, ButtonProps>(
  ({ className, variant = "secondary", type = "button", ...props }, ref) => {
    return (
      <button
        ref={ref}
        type={type}
        className={cn(
          "inline-flex items-center justify-center rounded-(--radius-sm) px-3.5 py-2 text-sm font-medium",
          "transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-accent focus-visible:ring-offset-2 focus-visible:ring-offset-background",
          "disabled:opacity-50 disabled:pointer-events-none",
          variantClasses[variant],
          className,
        )}
        {...props}
      />
    );
  },
);
Button.displayName = "Button";
