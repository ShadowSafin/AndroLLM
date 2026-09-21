"use client";

/**
 * Phase 4 expanded — head-to-head comparison cards.
 * Every comparison is computed from the user's own series; deltas are honest
 * (null-safe) and labeled with their windows.
 */
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import {
  dowName,
  formatCompact,
  formatGrowth,
  growthRate,
  hourLabel,
  peakSplit,
  sumDays,
  weekdaySplit,
} from "@/lib/dashboard-format";
import type { DayRow, HeatCell, ModelRow } from "@/lib/dashboard-client";
import { Line, LineChart, ResponsiveContainer } from "recharts";

function Row({ label, left, right, delta }: { label: string; left: string; right: string; delta?: string }) {
  return (
    <div className="flex items-center justify-between gap-3 py-1.5 text-sm">
      <span className="text-gray-400">{label}</span>
      <span className="flex items-center gap-2 tabular-nums">
        <span className="text-gray-100">{left}</span>
        <span className="text-gray-600">vs</span>
        <span className="text-gray-400">{right}</span>
        {delta ? <span className="w-16 text-right text-xs text-gray-400">{delta}</span> : null}
      </span>
    </div>
  );
}

function Panel({ title, desc, children }: { title: string; desc: string; children: React.ReactNode }) {
  return (
    <Card>
      <CardHeader>
        <CardTitle>{title}</CardTitle>
        <CardDescription>{desc}</CardDescription>
      </CardHeader>
      <CardContent>{children}</CardContent>
    </Card>
  );
}

export function WeekVsWeek({ perDay }: { perDay: DayRow[] }) {
  const cur = perDay.slice(-7);
  const prev = perDay.slice(-14, -7);
  const rows: [string, (d: DayRow) => number][] = [
    ["Chats", (d) => d.chats],
    ["Tokens", (d) => d.total_tokens],
    ["Sessions", (d) => d.sessions],
    ["Failures", (d) => d.errors],
  ];
  return (
    <Panel title="This week vs last" desc="Last 7 days against the prior 7">
      {rows.map(([label, pick]) => {
        const c = sumDays(cur, pick);
        const p = sumDays(prev, pick);
        const g = formatGrowth(growthRate(c, p));
        return <Row key={label} label={label} left={formatCompact(c)} right={formatCompact(p)} delta={g.text} />;
      })}
      <div className="mt-2 h-12">
        <ResponsiveContainer width="100%" height="100%">
          <LineChart data={cur.map((d) => ({ v: d.chats }))}>
            <Line type="monotone" dataKey="v" stroke="#f59e0b" strokeWidth={2} dot={false} />
          </LineChart>
        </ResponsiveContainer>
      </div>
    </Panel>
  );
}

export function MonthHalves({ perDay }: { perDay: DayRow[] }) {
  const cur = perDay.slice(-30);
  const prev = perDay.slice(-60, -30);
  const rows: [string, (d: DayRow) => number][] = [
    ["Chats", (d) => d.chats],
    ["Tokens", (d) => d.total_tokens],
    ["Sessions", (d) => d.sessions],
  ];
  return (
    <Panel title="Last 30d vs prior 30d" desc="Month-over-month style momentum">
      {rows.map(([label, pick]) => {
        const c = sumDays(cur, pick);
        const p = sumDays(prev, pick);
        const g = formatGrowth(growthRate(c, p));
        return <Row key={label} label={label} left={formatCompact(c)} right={formatCompact(p)} delta={g.text} />;
      })}
    </Panel>
  );
}

export function LocalVsCloud({ perDay }: { perDay: DayRow[] }) {
  const lc = sumDays(perDay, (d) => d.local_chats);
  const cc = sumDays(perDay, (d) => d.cloud_chats);
  const lt = sumDays(perDay, (d) => d.local_tokens);
  const ct = sumDays(perDay, (d) => d.cloud_tokens);
  return (
    <Panel title="Local vs cloud" desc="Window totals, chats and tokens">
      <Row label="Chats" left={formatCompact(lc)} right={formatCompact(cc)} />
      <Row label="Tokens" left={formatCompact(lt)} right={formatCompact(ct)} />
      <Row
        label="Token share"
        left={lt + ct > 0 ? `${Math.round((lt / (lt + ct)) * 100)}%` : "—"}
        right={lt + ct > 0 ? `${Math.round((ct / (lt + ct)) * 100)}%` : "—"}
      />
    </Panel>
  );
}

export function WeekdayVsWeekend({ heatmap }: { heatmap: HeatCell[] }) {
  const { weekday, weekend } = weekdaySplit(heatmap);
  return (
    <Panel title="Weekday vs weekend" desc="Mon–Fri against Sat–Sun">
      <Row label="Chats" left={formatCompact(weekday.chats)} right={formatCompact(weekend.chats)} />
      <Row label="Tokens" left={formatCompact(weekday.tokens)} right={formatCompact(weekend.tokens)} />
    </Panel>
  );
}

export function PeakVsOffHours({ heatmap }: { heatmap: HeatCell[] }) {
  const { peak, off } = peakSplit(heatmap);
  return (
    <Panel title="Peak vs off-hours" desc="Top-6 active hours against the rest">
      <Row label="Chats" left={formatCompact(peak.chats)} right={formatCompact(off.chats)} />
      <p className="pt-1 text-xs text-gray-500">
        Peak: {peak.hours.length > 0 ? peak.hours.map(hourLabel).join(", ") : "—"}
      </p>
    </Panel>
  );
}

export function FastestVsStable({ models }: { models: ModelRow[] }) {
  const timed = models.filter((m) => m.avg_latency_ms !== null && m.chats > 0);
  const fastest = [...timed].sort((a, b) => (a.avg_latency_ms ?? 0) - (b.avg_latency_ms ?? 0))[0];
  const stable = [...models.filter((m) => m.chats > 0)].sort((a, b) => {
    const fa = mFailed(a);
    const fb = mFailed(b);
    return fa - fb;
  })[0];
  function mFailed(m: ModelRow) {
    return m.chats > 0 ? m.failed / m.chats : 1;
  }
  return (
    <Panel title="Fastest vs most stable" desc="Lowest latency against lowest failure share">
      <Row label="Fastest" left={fastest?.model_name ?? "—"} right={fastest ? `${fastest.avg_latency_ms} ms` : "—"} />
      <Row
        label="Most stable"
        left={stable?.model_name ?? "—"}
        right={stable ? `${((1 - mFailed(stable)) * 100).toFixed(1)}% ok` : "—"}
      />
    </Panel>
  );
}

export function DayOfWeekNote({ heatmap }: { heatmap: HeatCell[] }) {
  const byDow = new Map<number, number>();
  for (const c of heatmap) byDow.set(c.dow, (byDow.get(c.dow) ?? 0) + c.chats);
  const ranked = [...byDow.entries()].sort((a, b) => b[1] - a[1]);
  return (
    <Panel title="Best day" desc="Highest chat volume by weekday">
      <p className="text-2xl font-semibold text-gray-100">
        {ranked.length > 0 ? dowName(ranked[0][0]) : "—"}
      </p>
      <p className="text-sm text-gray-400">
        {ranked.length > 0 ? `${formatCompact(ranked[0][1])} chats in window` : "No activity yet"}
      </p>
    </Panel>
  );
}
