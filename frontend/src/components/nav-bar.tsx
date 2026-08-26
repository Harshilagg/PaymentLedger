"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useAuth } from "@/lib/auth";
import { Button } from "@/components/ui/button";

export function NavBar() {
  const auth = useAuth();
  const router = useRouter();

  return (
    <header className="flex items-center justify-between gap-3 border-b border-outline-variant px-4 py-3 sm:px-6">
      {/* The way back to Accounts from anywhere, including a page that failed to load. */}
      <Link
        href="/"
        className="shrink-0 rounded-(--radius-sm) text-sm font-medium text-text-primary hover:text-accent focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent"
      >
        Payment Ledger
      </Link>
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
