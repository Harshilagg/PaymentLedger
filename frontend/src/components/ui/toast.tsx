"use client";

import * as RadixToast from "@radix-ui/react-toast";
import { createContext, useCallback, useContext, useMemo, useState, type ReactNode } from "react";
import { cn } from "@/lib/cn";

type ToastTone = "default" | "error";

interface ToastMessage {
  id: number;
  title: string;
  description?: string;
  tone: ToastTone;
}

interface ToastContextValue {
  publish: (toast: Omit<ToastMessage, "id" | "tone"> & { tone?: ToastTone }) => void;
}

const ToastContext = createContext<ToastContextValue | null>(null);

export function useToast() {
  const ctx = useContext(ToastContext);
  if (!ctx) throw new Error("useToast must be used within ToastProvider");
  return ctx;
}

const toneClasses: Record<ToastTone, string> = {
  default: "border-outline-variant bg-surface-container-lowest text-text-primary",
  error: "border-status-failed-text/30 bg-status-failed-bg text-status-failed-text",
};

export function ToastProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<ToastMessage[]>([]);

  const publish = useCallback(
    (toast: Omit<ToastMessage, "id" | "tone"> & { tone?: ToastTone }) => {
      setToasts((current) => [
        ...current,
        { tone: "default", ...toast, id: Date.now() + Math.random() },
      ]);
    },
    [],
  );

  const remove = useCallback((id: number) => {
    setToasts((current) => current.filter((t) => t.id !== id));
  }, []);

  const value = useMemo(() => ({ publish }), [publish]);

  return (
    <ToastContext.Provider value={value}>
      <RadixToast.Provider swipeDirection="right">
        {children}
        {toasts.map((toast) => (
          <RadixToast.Root
            key={toast.id}
            duration={5000}
            onOpenChange={(open) => {
              if (!open) remove(toast.id);
            }}
            className={cn(
              "rounded-(--radius-md) border px-4 py-3 shadow-lg",
              toneClasses[toast.tone],
            )}
          >
            <RadixToast.Title className="text-sm font-medium">{toast.title}</RadixToast.Title>
            {toast.description ? (
              <RadixToast.Description className="mt-1 text-sm opacity-80">
                {toast.description}
              </RadixToast.Description>
            ) : null}
          </RadixToast.Root>
        ))}
        <RadixToast.Viewport className="fixed bottom-4 right-4 z-50 flex w-full max-w-sm flex-col gap-2 outline-none" />
      </RadixToast.Provider>
    </ToastContext.Provider>
  );
}
