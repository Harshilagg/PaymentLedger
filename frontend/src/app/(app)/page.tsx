"use client";

import { useAuth } from "@/lib/auth";

// Placeholder for Stage 2 (shell only) — the real accounts/wallets list lands in Stage 3.
export default function HomePage() {
  const auth = useAuth();

  return (
    <div className="flex flex-1 items-center justify-center px-4">
      <p className="text-sm text-text-secondary">
        Signed in as <span className="font-mono text-text-primary">{auth.ownerId}</span>
      </p>
    </div>
  );
}
