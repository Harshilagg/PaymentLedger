"use client";

import { useRouter } from "next/navigation";
import { useAuth } from "@/lib/auth";
import { Button } from "@/components/ui/button";

export function NavBar() {
  const auth = useAuth();
  const router = useRouter();

  return (
    <header className="flex items-center justify-between gap-3 border-b border-outline-variant px-4 py-3 sm:px-6">
      <span className="shrink-0 text-sm font-medium text-text-primary">Payment Ledger</span>
      <div className="flex min-w-0 items-center gap-3">
        <span
          className="min-w-0 truncate font-mono text-xs text-text-secondary"
          title={auth.userId ?? undefined}
        >
          {auth.userId}
        </span>
        <Button
          variant="ghost"
          className="shrink-0"
          onClick={() => {
            auth.logout();
            router.replace("/login");
          }}
        >
          Sign out
        </Button>
      </div>
    </header>
  );
}
