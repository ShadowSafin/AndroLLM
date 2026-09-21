"use client";

/**
 * Phase 4 expanded — headline KPI grid (18 cards).
 * Account-wide totals come from the summary; trend cards derive from the
 * 90-day series client-side. Nothing here is global — all personal.
 */
import {
  Activity,
  AlertTriangle,
  ArrowDownRight,
  ArrowUpRight,
  CalendarDays,
  CheckCircle2,
  Cloud,
  Cpu,
  Crown,
  Flame,
  Gauge,
  Layers,
  MessagesSquare,
  RefreshCw,
  Smartphone,
  Timer,
  Trophy,
} from "lucide-react";
import { Card, CardContent } from "@/components/ui/card";
import {
  currentStreak,
  dayLabel,
  failureRate,
  formatCompact,
  formatDuration,
  formatGrowth,
  formatMs,
  formatPercent,
  growthRate,
  relativeTime,
  sliceRange,
  sumDays,
} from "@/lib/dashboard-format";
import type { DayRow, ModelRow, SummaryTotals } from "@/lib/dashboard-client";

function Stat({
  icon: Icon,
  label,
  value,
  sub,
  trend,
}: {
  icon: typeof Cpu;
  label: string;
  value: string;
  sub?: string;
  trend?: { text: string; up: boolean | null };
}) {
  const showTrend = trend !== undefined && trend.up !== null;
  const TrendIcon = trend?.up ? ArrowUpRight : ArrowDownRight;
  const trendColor = trend?.up ? "text-emerald-400" : "text-rose-400";
  const long = value.length > 12;
  return (
    <Card className="transition-colors hover:border-white/[0.14]">
      <CardContent className="flex min-h-[118px] flex-col justify-between gap-2.5 p-5">
        <span className="flex items-center gap-2">
          <Icon className="size-4 shrink-0 text-gray-400" aria-hidden />
          <span className="truncate text-[11px] font-medium uppercase tracking-wider text-gray-500 dark:text-gray-400">
            {label}
          </span>
        </span>
        <span className="flex items-end justify-between gap-2">
          <span
            className={`text-gray-100 tabular-nums ${
              long ? "break-all text-base font-semibold leading-snug" : "truncate text-2xl font-semibold tracking-tight"
            }`}
          >
            {value}
          </span>
          {showTrend ? (
            <span className={`flex shrink-0 items-center gap-0.5 text-xs font-medium ${trendColor}`}>
              <TrendIcon className="size-3.5" aria-hidden />
              {trend.text}
            </span>
          ) : null}
        </span>
        {sub ? <span className="truncate text-xs text-gray-500">{sub}</span> : null}
      </CardContent>
    </Card>
  );
}

export function KpiGrid({
  totals,
  perDay,
  models,
}: {
  totals: SummaryTotals;
  perDay: DayRow[];
  models: ModelRow[];
}) {
  const rate = failureRate(totals.failed, totals.events);
  const success = rate === null ? null : 1 - rate;
  const last7 = sliceRange(perDay, 7);
  const prev7 = perDay.slice(Math.max(0, perDay.length - 14), Math.max(0, perDay.length - 7));
  const last30 = sliceRange(perDay, 30);
  const prev30 = perDay.slice(Math.max(0, perDay.length - 60), Math.max(0, perDay.length - 30));
  const chatsW = formatGrowth(growthRate(sumDays(last7, (d) => d.chats), sumDays(prev7, (d) => d.chats)));
  const tokensM = formatGrowth(growthRate(sumDays(last30, (d) => d.total_tokens), sumDays(prev30, (d) => d.total_tokens)));
  const top = models[0];
  const streak = currentStreak(perDay);

  return (
    <div className="grid grid-cols-2 gap-3 md:grid-cols-3 xl:grid-cols-4 2xl:grid-cols-6" data-testid="kpi-grid">
      <Stat icon={MessagesSquare} label="Total chats" value={formatCompact(totals.chats)} trend={chatsW} />
      <Stat icon={Layers} label="Total tokens" value={formatCompact(totals.total_tokens)} trend={tokensM} />
      <Stat icon={Cpu} label="Local tokens" value={formatCompact(totals.local_tokens)} />
      <Stat icon={Cloud} label="Cloud tokens" value={formatCompact(totals.cloud_tokens)} />
      <Stat icon={Smartphone} label="Sessions" value={formatCompact(totals.sessions)} sub={`${totals.devices} device${totals.devices === 1 ? "" : "s"}`} />
      <Stat icon={Activity} label="Active devices" value={formatCompact(totals.devices)} />
      <Stat icon={Gauge} label="Avg latency" value={formatMs(totals.avg_latency_ms)} sub={totals.avg_ttft_ms !== null ? `First token ${formatMs(totals.avg_ttft_ms)}` : undefined} />
      <Stat icon={Timer} label="First token" value={formatMs(totals.avg_ttft_ms)} />
      <Stat icon={AlertTriangle} label="Failed" value={formatCompact(totals.failed)} sub={`${totals.events} synced events`} />
      <Stat icon={CheckCircle2} label="Success rate" value={formatPercent(success)} />
      <Stat icon={RefreshCw} label="Sync status" value="Linked" sub={`Synced ${relativeTime(totals.last_synced_at ?? totals.last_event_at)}`} />
      <Stat icon={Trophy} label="Models used" value={formatCompact(totals.models_count)} />
      <Stat icon={Crown} label="Most used" value={top ? (top.model_name.length > 14 ? `${top.model_name.slice(0, 13)}…` : top.model_name) : "—"} sub={top ? `${formatCompact(top.total_tokens)} tokens` : undefined} />
      <Stat icon={Timer} label="Longest session" value={formatDuration(totals.longest_session_seconds)} />
      <Stat icon={CalendarDays} label="Peak day" value={totals.peak_day ? dayLabel(totals.peak_day.day) : "—"} sub={totals.peak_day ? `${totals.peak_day.chats} chats` : undefined} />
      <Stat icon={Flame} label="Day streak" value={`${streak}`} sub={streak === 1 ? "day in a row" : "days in a row"} />
      <Stat icon={ArrowUpRight} label="Weekly growth" value={chatsW.text} sub="chats vs prior 7d" />
      <Stat icon={ArrowUpRight} label="Monthly growth" value={tokensM.text} sub="tokens vs prior 30d" />
    </div>
  );
}
