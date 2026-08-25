import { type HTMLAttributes } from "react";
import { cn } from "@/lib/cn";

export function Skeleton({ className, ...props }: HTMLAttributes<HTMLDivElement>) {
  return (
    <div
      className={cn("animate-pulse rounded-(--radius-sm) bg-surface-container-high", className)}
      {...props}
    />
  );
}
