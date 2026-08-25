"use client";

import { useEffect, type ReactNode } from "react";
import { useRouter } from "next/navigation";
import { useAuth } from "@/lib/auth";
import { NavBar } from "@/components/nav-bar";

export default function AppLayout({ children }: { children: ReactNode }) {
  const auth = useAuth();
  const router = useRouter();

  useEffect(() => {
    if (auth.ready && !auth.token) {
      router.replace("/login");
    }
  }, [auth.ready, auth.token, router]);

  if (!auth.ready || !auth.token) {
    return null;
  }

  return (
    <div className="flex flex-1 flex-col">
      <NavBar />
      <main className="flex flex-1 flex-col">{children}</main>
    </div>
  );
}
