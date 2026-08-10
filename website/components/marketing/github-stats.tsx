"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { Star, GitFork, CircleDot, Scale, GitPullRequest, Users } from "lucide-react";
import { site } from "@/lib/site";

type RepoStats = {
  stars: number;
  forks: number;
  issues: number;
  license: string;
  openPrs: number;
  contributors: number;
  source: "api" | "static";
};

const fallback: RepoStats = {
  stars: 0,
  forks: 0,
  issues: 0,
  license: "Apache-2.0",
  openPrs: 0,
  contributors: 0,
  source: "static",
};

function fmt(n: number): string {
  if (n >= 1_000_000) return (n / 1_000_000).toFixed(1).replace(/\.0$/, "") + "M";
  if (n >= 1_000) return (n / 1_000).toFixed(1).replace(/\.0$/, "") + "k";
  return String(n);
}

export function GitHubStats() {
  const [stats, setStats] = useState<RepoStats>(fallback);
  const [error, setError] = useState<boolean>(false);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const headers: Record<string, string> = {
          Accept: "application/vnd.github+json",
        };
        const [repoRes, prRes, contribRes] = await Promise.all([
          fetch(`https://api.github.com/repos/${site.ghOwner}/${site.ghRepo}`, { headers }),
          fetch(`https://api.github.com/search/issues?q=repo:${site.ghOwner}/${site.ghRepo}+type:pr+state:open`, { headers }),
          fetch(`https://api.github.com/repos/${site.ghOwner}/${site.ghRepo}/contributors?per_page=1&anon=1`, { headers }),
        ]);
        if (!repoRes.ok) throw new Error("repo");
        const repo = await repoRes.json();
        const pr = prRes.ok ? await prRes.json() : { total_count: 0 };
        const contrib = contribRes.ok ? contribRes.headers.get("link") : null;
        const lastPage = contrib ? /[?&]page=(\d+)>; rel="last"/.exec(contrib) : null;
        if (!cancelled) {
          setStats({
            stars: repo.stargazers_count ?? 0,
            forks: repo.forks_count ?? 0,
            issues: repo.open_issues_count ?? 0,
            license: repo.license?.spdx_id ?? "Apache-2.0",
            openPrs: pr.total_count ?? 0,
            contributors: lastPage ? Number(lastPage[1]) : 0,
            source: "api",
          });
        }
      } catch {
        if (!cancelled) setError(true);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  const cards = [
    { icon: Star, label: "Stars", value: fmt(stats.stars) },
    { icon: GitFork, label: "Forks", value: fmt(stats.forks) },
    { icon: CircleDot, label: "Open issues", value: fmt(stats.issues) },
    { icon: GitPullRequest, label: "Open PRs", value: fmt(stats.openPrs) },
    { icon: Users, label: "Contributors", value: stats.contributors > 0 ? fmt(stats.contributors) : "—" },
    { icon: Scale, label: "License", value: stats.license },
  ];

  return (
    <div>
      <div className="grid gap-4 sm:grid-cols-3">
        {cards.map((c) => (
          <div key={c.label} className="card p-5">
            <div className="flex items-center gap-2">
              <c.icon className="size-3.5 text-[var(--faint)]" aria-hidden />
              <p className="text-[11px] font-bold uppercase tracking-[0.16em] text-[var(--faint)]">{c.label}</p>
            </div>
            <p className="mt-2 font-mono text-2xl font-bold text-[var(--ink)]" data-hydration-safe>
              {c.value}
            </p>
          </div>
        ))}
      </div>
      <p className="mt-4 text-xs text-[var(--faint)]">
        {stats.source === "api"
          ? "Live numbers from the GitHub API."
          : error
            ? "Live GitHub numbers could not be fetched — showing known values. They update the moment you reload."
            : "Fetching live numbers from the GitHub API…"}
      </p>
      <div className="mt-6 flex flex-wrap gap-3">
        <Link href={site.repo} target="_blank" rel="noreferrer" className="btn btn-primary">
          <Star className="size-4" aria-hidden /> Star the repo
        </Link>
        <Link href={`${site.repo}/pulls`} target="_blank" rel="noreferrer" className="btn btn-secondary">
          Pull requests
        </Link>
        <Link href={`${site.repo}/discussions`} target="_blank" rel="noreferrer" className="btn btn-ghost">
          Discussions
        </Link>
      </div>
    </div>
  );
}