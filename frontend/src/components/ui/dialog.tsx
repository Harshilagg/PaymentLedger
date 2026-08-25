"use client";

import * as RadixDialog from "@radix-ui/react-dialog";
import { type ReactNode } from "react";
import { cn } from "@/lib/cn";

export const Dialog = RadixDialog.Root;
export const DialogTrigger = RadixDialog.Trigger;
export const DialogClose = RadixDialog.Close;

interface DialogContentProps {
  children: ReactNode;
  title: string;
  description?: string;
  className?: string;
}

export function DialogContent({ children, title, description, className }: DialogContentProps) {
  return (
    <RadixDialog.Portal>
      <RadixDialog.Overlay className="fixed inset-0 z-50 bg-text-primary/40" />
      <RadixDialog.Content
        className={cn(
          "fixed left-1/2 top-1/2 z-50 w-[calc(100%-2rem)] max-w-md -translate-x-1/2 -translate-y-1/2",
          "rounded-(--radius-md) border border-outline-variant bg-surface-container-lowest p-5 shadow-lg",
          "focus:outline-none",
          className,
        )}
      >
        <div className="flex items-start justify-between gap-4">
          <div>
            <RadixDialog.Title className="text-sm font-medium text-text-primary">
              {title}
            </RadixDialog.Title>
            {description ? (
              <RadixDialog.Description className="mt-1 text-sm text-text-secondary">
                {description}
              </RadixDialog.Description>
            ) : null}
          </div>
          <RadixDialog.Close
            aria-label="Close"
            className="shrink-0 rounded-(--radius-sm) p-1.5 text-lg leading-none text-text-secondary hover:bg-surface-container-low focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-accent"
          >
            ×
          </RadixDialog.Close>
        </div>
        <div className="mt-4">{children}</div>
      </RadixDialog.Content>
    </RadixDialog.Portal>
  );
}
