import Link from "next/link";
import { Fragment } from "react";
import { cn } from "@/lib/cn";

export interface Crumb {
  label: string;
  /** Omitted for the current page, and for a segment whose target isn't known yet. */
  href?: string;
  /** The untruncated value, surfaced on hover since `label` is usually a shortened id. */
  title?: string;
  /** Set for id segments, so they match the mono treatment ids get everywhere else. */
  mono?: boolean;
}

/**
 * The trail through Accounts -> Account -> Wallet -> Transaction. Callers pass only the segments
 * they actually know: while a resource is still loading, or when it failed to load, the segments
 * derived from it are simply left out rather than rendered as dead links. That matters most on an
 * error page, where the trail is the only way back out.
 */
export function Breadcrumbs({ items, className }: { items: Crumb[]; className?: string }) {
  if (items.length === 0) return null;

  return (
    <nav aria-label="Breadcrumb" className={cn("mb-4", className)}>
      {/* Wraps rather than scrolls: a long trail of ids has to stay fully readable at 375px. */}
      <ol className="flex flex-wrap items-center gap-x-1.5 gap-y-1 text-xs">
        {items.map((item, index) => {
          const isCurrent = index === items.length - 1;
          return (
            <Fragment key={`${item.href ?? "current"}-${item.label}`}>
              {index > 0 ? (
                <li aria-hidden="true" className="text-text-secondary">
                  /
                </li>
              ) : null}
              <li className="min-w-0">
                {item.href && !isCurrent ? (
                  <Link
                    href={item.href}
                    title={item.title}
                    className={cn(
                      "rounded-(--radius-sm) text-accent hover:underline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent",
                      item.mono && "font-mono",
                    )}
                  >
                    {item.label}
                  </Link>
                ) : (
                  <span
                    aria-current={isCurrent ? "page" : undefined}
                    title={item.title}
                    className={cn("text-text-secondary", item.mono && "font-mono")}
                  >
                    {item.label}
                  </span>
                )}
              </li>
            </Fragment>
          );
        })}
      </ol>
    </nav>
  );
}
