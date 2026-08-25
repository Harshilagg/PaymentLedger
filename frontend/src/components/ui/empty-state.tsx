import { type ReactNode } from "react";
import { cn } from "@/lib/cn";

interface EmptyStateProps {
  title: string;
  description?: string;
  action?: ReactNode;
  className?: string;
}

export function EmptyState({ title, description, action, className }: EmptyStateProps) {
  return (
    <div
      className={cn(
        "flex flex-col items-center justify-center gap-1 rounded-(--radius-md) border border-dashed border-outline-variant px-6 py-12 text-center",
        className,
      )}
    >
      <p className="text-sm font-medium text-text-primary">{title}</p>
      {description ? <p className="text-sm text-text-secondary">{description}</p> : null}
      {action ? <div className="mt-3">{action}</div> : null}
    </div>
  );
}
