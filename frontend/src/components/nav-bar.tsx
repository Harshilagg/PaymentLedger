"use client";

import { useRouter } from "next/navigation";
import { useAuth } from "@/lib/auth";
import { Button } from "@/components/ui/button";

export function NavBar() {
  const auth = useAuth();
  const router = useRouter();

  return (
    <header className="flex items-center justify-between border-b border-outline-variant px-6 py-3">
      <span className="text-sm font-medium text-text-primary">Payment Ledger</span>
      <div className="flex items-center gap-3">
        <span className="font-mono text-xs text-text-secondary" title={auth.ownerId ?? undefined}>
          {auth.ownerId}
        </span>
        <Button
          variant="ghost"
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
