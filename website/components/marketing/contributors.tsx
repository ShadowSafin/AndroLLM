"use client";

import { useEffect, useState } from "react";
import { Users, UserX } from "lucide-react";
import { site } from "@/lib/site";

type Contributor = { login: string; avatar: string; url: string; contributions: number };

export function Contributors() {
  const [list, setList] = useState<Contributor[] | null>(null);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const res = await fetch(
          `https://api.github.com/repos/${site.ghOwner}/${site.ghRepo}/contributors?per_page=12`,
          { headers: { Accept: "application/vnd.github+json" } }
        );
        if (!res.ok) throw new Error("contributors");
        const data = (await res.json()) as Array<{
          login: string;
          avatar_url: string;
          html_url: string;
          contributions: number;
        }>;
        if (!cancelled) {
          setList(
            data.map((c) => ({
              login: c.login,
              avatar: c.avatar_url,
              url: c.html_url,
              contributions: c.contributions,
            }))
          );
        }
      } catch {
        if (!cancelled) setFailed(true);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  const count = list?.length ?? 0;

  return (
    <div>
      <div className="flex items-center gap-2.5 text-xs font-bold uppercase tracking-[0.16em] text-[var(--faint)]">
        <Users className="size-3.5" aria-hidden />
        Contributors{list ? ` (${count})` : failed ? "" : " — loading…"}
      </div>

      {list && list.length > 0 ? (
        <ul className="mt-5 grid gap-3 sm:grid-cols-2" data-hydration-safe>
          {list.map((c) => (
            <li key={c.login}>
              <a
                href={c.url}
                target="_blank"
                rel="noreferrer"
                className="card flex items-center gap-3 p-4 transition-colors hover:border-[var(--accent)]"
              >
                <img
                  src={c.avatar}
                  alt={`${c.login} avatar`}
                  width={40}
                  height={40}
                  className="size-10 rounded-full border border-[var(--line)]"
                  loading="lazy"
                />
                <div className="min-w-0 flex-1">
                  <p className="truncate text-sm font-semibold text-[var(--ink)]">{c.login}</p>
                  <p className="text-xs text-[var(--faint)]">{c.contributions} commits</p>
                </div>
              </a>
            </li>
          ))}
        </ul>
      ) : (
        <div className="card mt-5 flex items-center gap-3 p-5">
          <UserX className="size-4 shrink-0 text-[var(--faint)]" aria-hidden />
          <p className="text-sm text-[var(--muted)]">
            {failed
              ? "Couldn’t load the live contributor list. This project is early — you could be first on it. The repo’s commit graph tells the real story."
              : "Loading the live list from GitHub…"}
          </p>
        </div>
      )}

      <a
        href={`${site.repo}/graphs/contributors`}
        target="_blank"
        rel="noreferrer"
        className="mt-6 inline-flex items-center gap-2 text-sm font-bold text-[var(--accent-deep)] hover:underline dark:text-[var(--accent-soft)]"
      >
        <Users className="size-3.5" aria-hidden />
        View the full commit graph →
      </a>
    </div>
  );
}