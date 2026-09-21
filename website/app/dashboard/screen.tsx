"use client";

/**
 * Phase 4 expanded — full-page analytics experience.
 *
 * One scrollable page, five tabs (Overview / Models / Patterns / Fleet /
 * Activity), a filter bar (time range, engine, model), and a data-notes
 * footer. Everything shown is the signed-in user's own usage; connection,
 * loading, error, and empty states match the original screen's testids.
 */
import Link from "next/link";
import { useMemo, useState } from "react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { ActivityView } from "@/components/dashboard/activity";
import { DashboardChartsView } from "@/components/dashboard/charts";
import { KpiGrid } from "@/components/dashboard/kpi";
import {
  AppVersionChart,
  DayOfWeekChart,
  Heatmap,
  HourlyChart,
  LocalCloudChats,
  ModelErrorChart,
  ModelSpeedChart,
  ProviderChart,
  SessionDurationChart,
  SuccessRateChart,
  TokenHistogram,
  TtftChart,
  WeeklyChart,
} from "@/components/dashboard/patterns";
import {
  DayOfWeekNote,
  FastestVsStable,
  LocalVsCloud,
  MonthHalves,
  PeakVsOffHours,
  WeekVsWeek,
  WeekdayVsWeekend,
} from "@/components/dashboard/compare";
import {
  DeviceLeaderboard,
  ErrorLeaderboard,
  ModelLeaderboard,
  SessionLeaderboard,
} from "@/components/dashboard/tables";
import { useBackendUser } from "@/hooks/use-backend-user";
import { useDashboard } from "@/hooks/use-dashboard";
import { formatCompact, relativeTime, sliceRange, type RangeKey } from "@/lib/dashboard-format";
import type { DashboardActivity, DashboardCharts, DashboardSummary } from "@/lib/dashboard-client";

type Tab = "overview" | "models" | "patterns" | "fleet" | "activity";
type EngineFilter = "all" | "local" | "cloud";

const TABS: { id: Tab; label: string }[] = [
  { id: "overview", label: "Overview" },
  { id: "models", label: "Models" },
  { id: "patterns", label: "Patterns" },
  { id: "fleet", label: "Devices & Sessions" },
  { id: "activity", label: "Activity" },
];

function Shell({ children }: { children: React.ReactNode }) {
  return (
    <main className="container flex min-h-screen flex-col gap-6 py-24">
      {children}
    </main>
  );
}

function SkeletonGrid() {
  return (
    <div className="grid grid-cols-2 gap-3 md:grid-cols-4" aria-label="Loading dashboard">
      {Array.from({ length: 7 }).map((_, i) => (
        <div key={i} className="h-24 animate-pulse rounded-2xl bg-white/[0.04]" />
      ))}
      <div className="col-span-2 h-64 animate-pulse rounded-2xl bg-white/[0.04] md:col-span-4" />
    </div>
  );
}

export function DashboardScreen() {
  const { firebaseUser, profile } = useBackendUser();
  const { status, summary, charts, activity, error, refresh } = useDashboard();

  if (status === "loading") {
    return (
      <Shell>
        <SkeletonGrid />
      </Shell>
    );
  }

  if (status === "signed-out") {
    return (
      <Shell>
        <Card className="mx-auto w-full max-w-lg" data-testid="dashboard-signed-out">
          <CardHeader>
            <CardTitle>Sign in to view your dashboard</CardTitle>
            <CardDescription>
              Use the same Firebase account as your Android app.
            </CardDescription>
          </CardHeader>
          <CardContent>
            <Button asChild>
              <Link href="/account">Go to Account</Link>
            </Button>
          </CardContent>
        </Card>
      </Shell>
    );
  }

  if (status === "not-connected") {
    return (
      <Shell>
        <Card className="mx-auto w-full max-w-lg" data-testid="dashboard-not-connected">
          <CardHeader>
            <div className="flex items-center justify-between gap-4">
              <CardTitle>Connect your Android app</CardTitle>
              <Badge variant="secondary">Not linked</Badge>
            </div>
            <CardDescription>
              Connect your Android app to start syncing analytics.
            </CardDescription>
          </CardHeader>
          <CardContent className="flex flex-col gap-4">
            <ol className="list-decimal space-y-1 pl-5 text-sm text-gray-400">
              <li>Open the AndroLLM app on your Android device.</li>
              <li>Go to Settings → Account &amp; Sync.</li>
              <li>Tap <strong>Connect Web Dashboard</strong> and confirm.</li>
              <li>Then come back here and refresh.</li>
            </ol>
            <div className="flex gap-2">
              <Button onClick={() => void refresh()}>Recheck status</Button>
              <Button variant="secondary" asChild>
                <Link href="/account">Account</Link>
              </Button>
            </div>
          </CardContent>
        </Card>
      </Shell>
    );
  }

  if (status === "error") {
    return (
      <Shell>
        <Card className="mx-auto w-full max-w-lg" data-testid="dashboard-error">
          <CardHeader>
            <CardTitle>Couldn&apos;t load your dashboard</CardTitle>
            <CardDescription>{error ?? "Something went wrong."}</CardDescription>
          </CardHeader>
          <CardContent>
            <Button onClick={() => void refresh()}>Try again</Button>
          </CardContent>
        </Card>
      </Shell>
    );
  }

  if (status === "empty") {
    return (
      <Shell>
        <Card className="mx-auto w-full max-w-lg" data-testid="dashboard-empty">
          <CardHeader>
            <div className="flex items-center justify-between gap-4">
              <CardTitle>No analytics yet</CardTitle>
              <Badge variant="ember">Linked</Badge>
            </div>
            <CardDescription>
              Your app is connected{firebaseUser?.email ? ` as ${firebaseUser.email}` : ""}.
              Once you chat in the app, totals, charts, and activity will appear here.
            </CardDescription>
          </CardHeader>
          <CardContent className="flex flex-col gap-4">
            <ul className="list-disc space-y-1 pl-5 text-sm text-gray-400">
              <li>Chat with a local or cloud model in the app.</li>
              <li>Keep the device online so queued events can sync.</li>
              <li>Come back here — your stats update on refresh.</li>
            </ul>
            <div>
              <Button onClick={() => void refresh()}>Refresh</Button>
            </div>
          </CardContent>
        </Card>
      </Shell>
    );
  }

  if (!summary || !charts || !activity) {
    return (
      <Shell>
        <SkeletonGrid />
      </Shell>
    );
  }

  return (
    <ReadyView
      summary={summary}
      charts={charts}
      activity={activity}
      email={firebaseUser?.email ?? null}
      connectedAt={profile?.web_connected_at ?? null}
      onRefresh={() => void refresh()}
    />
  );
}

function ReadyView({
  summary,
  charts,
  activity,
  email,
  connectedAt,
  onRefresh,
}: {
  summary: DashboardSummary;
  charts: DashboardCharts;
  activity: DashboardActivity;
  email: string | null;
  connectedAt: string | null;
  onRefresh: () => void;
}) {
  const [tab, setTab] = useState<Tab>("overview");
  const [range, setRange] = useState<RangeKey>(30);
  const [engine, setEngine] = useState<EngineFilter>("all");
  const [model, setModel] = useState<string>("all");

  const ranged = useMemo(() => sliceRange(charts.per_day, range), [charts, range]);
  const filteredModels = useMemo(
    () =>
      charts.models.filter(
        (m) => (engine === "all" || m.engine_type === engine) && (model === "all" || m.model_name === model),
      ),
    [charts, engine, model],
  );
  const filteredActivity = useMemo(
    () => ({
      ...activity,
      events: activity.events.filter(
        (e) => (engine === "all" || e.engine_type === engine) && (model === "all" || e.model_name === model),
      ),
    }),
    [activity, engine, model],
  );
  const platformSplit = useMemo(() => {
    const map = new Map<string, number>();
    for (const d of charts.by_device) {
      map.set(d.platform, (map.get(d.platform) ?? 0) + d.chats);
    }
    return [...map.entries()];
  }, [charts]);

  return (
    <Shell>
      {/* Hero */}
      <div className="flex flex-wrap items-end justify-between gap-4" data-testid="dashboard-ready">
        <div>
          <div className="flex flex-wrap items-center gap-2">
            <h1 className="text-3xl font-semibold tracking-tight text-gray-50">Usage intelligence</h1>
            <Badge variant="ember">Linked</Badge>
          </div>
          <p className="mt-1 text-sm text-gray-400">
            {email ?? "Your account"}
            {connectedAt ? ` · linked since ${connectedAt.slice(0, 10)}` : ""}
            {summary.totals.last_synced_at ? ` · synced ${relativeTime(summary.totals.last_synced_at)}` : ""}
          </p>
        </div>
        <div className="flex items-center gap-2">
          <Button variant="secondary" size="sm" onClick={onRefresh}>
            Refresh
          </Button>
          <Button variant="secondary" size="sm" asChild>
            <Link href="/account">Account</Link>
          </Button>
        </div>
      </div>

      {/* Filter bar */}
      <div className="flex flex-wrap items-center gap-2 rounded-2xl border border-white/[0.06] bg-white/[0.02] p-3">
        <div className="flex items-center gap-1" role="group" aria-label="Time range">
          {([7, 30, 90] as RangeKey[]).map((r) => (
            <button
              key={r}
              onClick={() => setRange(r)}
              className={`rounded-full px-3 py-1.5 text-xs font-medium transition-colors ${
                range === r ? "bg-white/[0.12] text-gray-100" : "text-gray-400 hover:text-gray-200"
              }`}
            >
              {r}D
            </button>
          ))}
        </div>
        <span className="h-5 w-px bg-white/10" aria-hidden />
        <div className="flex items-center gap-1" role="group" aria-label="Engine">
          {(["all", "local", "cloud"] as EngineFilter[]).map((e) => (
            <button
              key={e}
              onClick={() => setEngine(e)}
              className={`rounded-full px-3 py-1.5 text-xs font-medium capitalize transition-colors ${
                engine === e ? "bg-white/[0.12] text-gray-100" : "text-gray-400 hover:text-gray-200"
              }`}
            >
              {e}
            </button>
          ))}
        </div>
        <span className="h-5 w-px bg-white/10" aria-hidden />
        <select
          aria-label="Model"
          value={model}
          onChange={(e) => setModel(e.target.value)}
          className="rounded-full border border-white/10 bg-black px-3 py-1.5 text-xs text-gray-200"
        >
          <option value="all">All models</option>
          {charts.models.map((m) => (
            <option key={`${m.model_name}|${m.provider_name ?? ""}`} value={m.model_name}>
              {m.model_name}
            </option>
          ))}
        </select>
        <span className="ml-auto hidden text-xs text-gray-500 md:inline">
          {formatCompact(summary.totals.chats)} chats · {formatCompact(summary.totals.total_tokens)} tokens all time
        </span>
      </div>

      {/* Tabs */}
      <div className="flex flex-wrap gap-1 border-b border-white/[0.06]" role="tablist" aria-label="Dashboard sections">
        {TABS.map((t) => (
          <button
            key={t.id}
            role="tab"
            aria-selected={tab === t.id}
            onClick={() => setTab(t.id)}
            className={`-mb-px border-b-2 px-4 py-2.5 text-sm font-medium transition-colors ${
              tab === t.id
                ? "border-amber-400 text-gray-100"
                : "border-transparent text-gray-500 hover:text-gray-300"
            }`}
          >
            {t.label}
          </button>
        ))}
      </div>

      {tab === "overview" && (
        <div className="flex flex-col gap-4">
          <KpiGrid totals={summary.totals} perDay={charts.per_day} models={charts.models} />
          <DashboardChartsView charts={{ per_day: ranged, models: charts.models }} />
          <div className="grid grid-cols-1 gap-3 md:grid-cols-3">
            <WeekVsWeek perDay={charts.per_day} />
            <MonthHalves perDay={charts.per_day} />
            <LocalVsCloud perDay={charts.per_day} />
          </div>
        </div>
      )}

      {tab === "models" && (
        <div className="flex flex-col gap-4">
          <div className="grid grid-cols-1 gap-3 lg:grid-cols-2">
            <ModelLeaderboard models={filteredModels} />
            <FastestVsStable models={charts.models} />
          </div>
          <div className="grid grid-cols-1 gap-3 lg:grid-cols-2">
            <ModelSpeedChart models={filteredModels} />
            <ModelErrorChart models={filteredModels} />
          </div>
          <ProviderChart providers={charts.providers} />
        </div>
      )}

      {tab === "patterns" && (
        <div className="flex flex-col gap-4">
          <WeeklyChart perDay={ranged} />
          <Heatmap cells={charts.heatmap} />
          <div className="grid grid-cols-1 gap-3 lg:grid-cols-2">
            <HourlyChart cells={charts.heatmap} />
            <DayOfWeekChart cells={charts.heatmap} />
          </div>
          <div className="grid grid-cols-1 gap-3 md:grid-cols-3">
            <WeekdayVsWeekend heatmap={charts.heatmap} />
            <PeakVsOffHours heatmap={charts.heatmap} />
            <DayOfWeekNote heatmap={charts.heatmap} />
          </div>
          <div className="grid grid-cols-1 gap-3 lg:grid-cols-2">
            <TokenHistogram buckets={charts.token_histogram} />
            <SessionDurationChart buckets={charts.session_durations} />
          </div>
          <div className="grid grid-cols-1 gap-3 lg:grid-cols-2">
            <SuccessRateChart perDay={ranged} />
            <TtftChart perDay={ranged} />
          </div>
          <LocalCloudChats perDay={ranged} />
        </div>
      )}

      {tab === "fleet" && (
        <div className="flex flex-col gap-4">
          <div className="grid grid-cols-1 gap-3 lg:grid-cols-2">
            <DeviceLeaderboard rows={charts.by_device} />
            <SessionLeaderboard sessions={activity.sessions} />
          </div>
          <div className="grid grid-cols-1 gap-3 lg:grid-cols-2">
            <AppVersionChart versions={charts.app_versions} />
            <Card>
              <CardHeader>
                <CardTitle>Platforms</CardTitle>
                <CardDescription>Chat share by platform (90d)</CardDescription>
              </CardHeader>
              <CardContent className="flex flex-col gap-2">
                {platformSplit.length === 0 ? (
                  <p className="py-8 text-center text-sm text-gray-500">No platform data.</p>
                ) : (
                  platformSplit.map(([platform, chats]) => (
                    <div key={platform} className="flex items-center justify-between text-sm">
                      <span className="text-gray-300">{platform}</span>
                      <span className="tabular-nums text-gray-100">{formatCompact(chats)} chats</span>
                    </div>
                  ))
                )}
                <div className="mt-2 border-t border-white/[0.06] pt-3">
                  <p className="text-xs text-gray-500">
                    Sync sources:{" "}
                    {summary.totals.by_source.length > 0
                      ? summary.totals.by_source.map((s) => `${s.source_page ?? "unknown"} (${formatCompact(s.events)})`).join(" · ")
                      : "—"}
                  </p>
                </div>
              </CardContent>
            </Card>
          </div>
        </div>
      )}

      {tab === "activity" && (
        <div className="flex flex-col gap-4">
          <ActivityView activity={filteredActivity} />
          <ErrorLeaderboard events={filteredActivity.events} />
        </div>
      )}

      {/* Footer notes */}
      <details className="rounded-2xl border border-white/[0.06] bg-white/[0.02] p-4 text-sm text-gray-400">
        <summary className="cursor-pointer font-medium text-gray-200">Data & sync notes</summary>
        <ul className="mt-2 list-disc space-y-1 pl-5 text-[13px]">
          <li>Everything here is your own synced usage — never another account&apos;s.</li>
          <li>Headline cards are all-time; charts default to the selected range (windowed breakdowns cover 90d).</li>
          <li>New generations sync within ~10s; this page refreshes itself every 10s while visible.</li>
          <li>Prompt text is never uploaded — only counts, timings, model names, and error codes.</li>
        </ul>
      </details>
    </Shell>
  );
}
