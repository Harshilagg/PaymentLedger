"use client";

import * as RadixDialog from "@radix-ui/react-dialog";
import { type ReactNode } from "react";
import { cn } from "@/lib/cn";

export const Sheet = RadixDialog.Root;
export const SheetTrigger = RadixDialog.Trigger;
export const SheetClose = RadixDialog.Close;

interface SheetContentProps {
  children: ReactNode;
  title: string;
  description?: string;
  className?: string;
}

export function SheetContent({ children, title, description, className }: SheetContentProps) {
  return (
    <RadixDialog.Portal>
      <RadixDialog.Overlay className="fixed inset-0 z-50 bg-text-primary/40" />
      <RadixDialog.Content
        className={cn(
          "fixed inset-y-0 right-0 z-50 flex h-full w-full max-w-md flex-col",
          "border-l border-outline-variant bg-surface-container-lowest p-5 shadow-lg",
          "focus:outline-none",
          className,
        )}
      >
        <RadixDialog.Title className="text-sm font-medium text-text-primary">
          {title}
        </RadixDialog.Title>
        {description ? (
          <RadixDialog.Description className="mt-1 text-sm text-text-secondary">
            {description}
          </RadixDialog.Description>
        ) : null}
        <div className="mt-4 flex-1 overflow-y-auto">{children}</div>
      </RadixDialog.Content>
    </RadixDialog.Portal>
  );
}
