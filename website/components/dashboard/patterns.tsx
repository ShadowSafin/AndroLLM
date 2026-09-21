"use client";

/**
 * Phase 4 expanded — usage-pattern charts: weekly trend, activity heatmap,
 * hour/day distributions, token histogram, session durations, providers,
 * app versions, success-rate and first-token trends.
 */
import {
  Bar,
  BarChart,
  CartesianGrid,
  Cell,
  Legend,
  Line,
  LineChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import {
  DURATION_BUCKETS,
  TOKEN_BUCKETS,
  dayLabel,
  dowName,
  formatCompact,
  groupByWeek,
  hourLabel,
  orderBuckets,
} from "@/lib/dashboard-format";
import type {
  AppVersionRow,
  BucketCount,
  DayRow,
  HeatCell,
  ModelRow,
  ProviderRow,
} from "@/lib/dashboard-client";

const GRID = "rgba(255,255,255,0.08)";
const TICK = { fill: "#a1a1aa", fontSize: 12 };
const TOOLTIP = {
  backgroundColor: "#18181b",
  border: "1px solid rgba(255,255,255,0.12)",
  borderRadius: 12,
  color: "#f4f4f5",
  fontSize: 12,
};
const LOCAL = "#34d399";
const CLOUD = "#60a5fa";
const ACCENT = "#f59e0b";
const VIOLET = "#a78bfa";
const ROSE = "#fb7185";

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

function Empty({ what }: { what: string }) {
  return <p className="py-16 text-center text-sm text-gray-500">No {what} in this window.</p>;
}

export function WeeklyChart({ perDay }: { perDay: DayRow[] }) {
  const weeks = groupByWeek(perDay).slice(-12);
  return (
    <Panel title="Weekly trend" desc="Chats, sessions and errors per week">
      {weeks.length > 0 ? (
        <ResponsiveContainer width="100%" height={260}>
          <BarChart data={weeks} margin={{ left: -8, right: 8 }}>
            <CartesianGrid stroke={GRID} vertical={false} />
            <XAxis dataKey="label" tick={TICK} tickLine={false} axisLine={false} minTickGap={16} />
            <YAxis tick={TICK} tickLine={false} axisLine={false} allowDecimals={false} />
            <Tooltip contentStyle={TOOLTIP} />
            <Legend />
            <Bar dataKey="chats" name="Chats" fill={ACCENT} radius={[4, 4, 0, 0]} />
            <Bar dataKey="sessions" name="Sessions" fill={VIOLET} radius={[4, 4, 0, 0]} />
            <Bar dataKey="errors" name="Failures" fill={ROSE} radius={[4, 4, 0, 0]} />
          </BarChart>
        </ResponsiveContainer>
      ) : (
        <Empty what="weekly data" />
      )}
    </Panel>
  );
}

export function Heatmap({ cells }: { cells: HeatCell[] }) {
  const max = cells.reduce((n, c) => Math.max(n, c.chats), 0);
  const grid: number[][] = Array.from({ length: 7 }, () => Array(24).fill(0));
  for (const c of cells) {
    if (c.dow >= 0 && c.dow < 7 && c.hour >= 0 && c.hour < 24) grid[c.dow][c.hour] += c.chats;
  }
  if (max === 0) {
    return (
      <Panel title="Activity heatmap" desc="Chats by weekday and hour (UTC)">
        <Empty what="activity" />
      </Panel>
    );
  }
  return (
    <Panel title="Activity heatmap" desc="Chats by weekday and hour (UTC)">
      <div className="overflow-x-auto" data-testid="heatmap">
        <div className="grid min-w-[560px] grid-cols-[44px_repeat(24,1fr)] gap-1">
          <span />
          {Array.from({ length: 24 }, (_, h) => (
            <span key={h} className="text-center text-[10px] text-gray-500">
              {h % 3 === 0 ? hourLabel(h) : ""}
            </span>
          ))}
          {grid.map((row, dow) => (
            <RowCells key={dow} dow={dow} row={row} max={max} />
          ))}
        </div>
      </div>
    </Panel>
  );
}

function RowCells({ dow, row, max }: { dow: number; row: number[]; max: number }) {
  return (
    <>
      <span className="self-center text-xs text-gray-400">{dowName(dow)}</span>
      {row.map((v, h) => (
        <span
          key={h}
          title={`${dowName(dow)} ${hourLabel(h)}: ${v} chats`}
          className="aspect-square rounded-[4px]"
          style={{ backgroundColor: `rgba(245,158,11,${v === 0 ? 0.06 : 0.15 + (0.85 * v) / max})` }}
        />
      ))}
    </>
  );
}

export function HourlyChart({ cells }: { cells: HeatCell[] }) {
  const hours = Array.from({ length: 24 }, (_, hour) => ({
    label: hourLabel(hour),
    chats: cells.filter((c) => c.hour === hour).reduce((n, c) => n + c.chats, 0),
  }));
  return (
    <Panel title="Usage by hour" desc="When during the day you chat">
      <ResponsiveContainer width="100%" height={220}>
        <BarChart data={hours} margin={{ left: -16, right: 8 }}>
          <CartesianGrid stroke={GRID} vertical={false} />
          <XAxis dataKey="label" tick={TICK} tickLine={false} axisLine={false} interval={2} />
          <YAxis tick={TICK} tickLine={false} axisLine={false} allowDecimals={false} />
          <Tooltip contentStyle={TOOLTIP} />
          <Bar dataKey="chats" fill={ACCENT} radius={[3, 3, 0, 0]} />
        </BarChart>
      </ResponsiveContainer>
    </Panel>
  );
}

export function DayOfWeekChart({ cells }: { cells: HeatCell[] }) {
  const days = Array.from({ length: 7 }, (_, dow) => ({
    label: dowName(dow),
    chats: cells.filter((c) => c.dow === dow).reduce((n, c) => n + c.chats, 0),
  }));
  return (
    <Panel title="Usage by weekday" desc="Which days carry the load">
      <ResponsiveContainer width="100%" height={220}>
        <BarChart data={days} margin={{ left: -16, right: 8 }}>
          <CartesianGrid stroke={GRID} vertical={false} />
          <XAxis dataKey="label" tick={TICK} tickLine={false} axisLine={false} />
          <YAxis tick={TICK} tickLine={false} axisLine={false} allowDecimals={false} />
          <Tooltip contentStyle={TOOLTIP} />
          <Bar dataKey="chats" fill={VIOLET} radius={[3, 3, 0, 0]} />
        </BarChart>
      </ResponsiveContainer>
    </Panel>
  );
}

export function TokenHistogram({ buckets }: { buckets: BucketCount[] }) {
  const data = orderBuckets(buckets, TOKEN_BUCKETS);
  return (
    <Panel title="Tokens per generation" desc="Chat size distribution">
      <ResponsiveContainer width="100%" height={220}>
        <BarChart data={data} margin={{ left: -16, right: 8 }}>
          <CartesianGrid stroke={GRID} vertical={false} />
          <XAxis dataKey="bucket" tick={TICK} tickLine={false} axisLine={false} />
          <YAxis tick={TICK} tickLine={false} axisLine={false} allowDecimals={false} />
          <Tooltip contentStyle={TOOLTIP} />
          <Bar dataKey="count" name="Chats" fill={LOCAL} radius={[3, 3, 0, 0]} />
        </BarChart>
      </ResponsiveContainer>
    </Panel>
  );
}

export function SessionDurationChart({ buckets }: { buckets: BucketCount[] }) {
  const data = orderBuckets(buckets, DURATION_BUCKETS);
  return (
    <Panel title="Session lengths" desc="How long sessions run">
      <ResponsiveContainer width="100%" height={220}>
        <BarChart data={data} margin={{ left: -16, right: 8 }}>
          <CartesianGrid stroke={GRID} vertical={false} />
          <XAxis dataKey="bucket" tick={TICK} tickLine={false} axisLine={false} />
          <YAxis tick={TICK} tickLine={false} axisLine={false} allowDecimals={false} />
          <Tooltip contentStyle={TOOLTIP} />
          <Bar dataKey="count" name="Sessions" fill={CLOUD} radius={[3, 3, 0, 0]} />
        </BarChart>
      </ResponsiveContainer>
    </Panel>
  );
}

export function ProviderChart({ providers }: { providers: ProviderRow[] }) {
  return (
    <Panel title="Cloud providers" desc="Tokens per provider (90d)">
      {providers.length > 0 ? (
        <ResponsiveContainer width="100%" height={220}>
          <BarChart data={providers.map((p) => ({ name: p.provider_name, tokens: p.total_tokens, chats: p.chats }))} layout="vertical" margin={{ left: 8, right: 16 }}>
            <CartesianGrid stroke={GRID} horizontal={false} />
            <XAxis type="number" tick={TICK} tickLine={false} axisLine={false} tickFormatter={formatCompact} />
            <YAxis type="category" dataKey="name" tick={TICK} tickLine={false} axisLine={false} width={110} />
            <Tooltip contentStyle={TOOLTIP} formatter={(v) => formatCompact(Number(v))} />
            <Bar dataKey="tokens" name="Tokens" fill={CLOUD} radius={[0, 4, 4, 0]} />
          </BarChart>
        </ResponsiveContainer>
      ) : (
        <Empty what="cloud provider usage" />
      )}
    </Panel>
  );
}

export function AppVersionChart({ versions }: { versions: AppVersionRow[] }) {
  return (
    <Panel title="App versions" desc="Chats per build (90d)">
      {versions.length > 0 ? (
        <ResponsiveContainer width="100%" height={220}>
          <BarChart data={versions.map((v) => ({ name: `v${v.app_version}`, chats: v.chats, tokens: v.total_tokens }))} margin={{ left: -16, right: 8 }}>
            <CartesianGrid stroke={GRID} vertical={false} />
            <XAxis dataKey="name" tick={TICK} tickLine={false} axisLine={false} />
            <YAxis tick={TICK} tickLine={false} axisLine={false} allowDecimals={false} />
            <Tooltip contentStyle={TOOLTIP} />
            <Bar dataKey="chats" name="Chats" fill={ACCENT} radius={[3, 3, 0, 0]} />
          </BarChart>
        </ResponsiveContainer>
      ) : (
        <Empty what="version data" />
      )}
    </Panel>
  );
}

export function SuccessRateChart({ perDay }: { perDay: DayRow[] }) {
  const data = perDay.map((d) => ({
    label: dayLabel(d.day),
    rate: d.events > 0 ? (100 * d.successes) / d.events : null,
  }));
  return (
    <Panel title="Success rate" desc="Share of successful events per day">
      <ResponsiveContainer width="100%" height={220}>
        <LineChart data={data} margin={{ left: -8, right: 8 }}>
          <CartesianGrid stroke={GRID} vertical={false} />
          <XAxis dataKey="label" tick={TICK} tickLine={false} axisLine={false} minTickGap={24} />
          <YAxis tick={TICK} tickLine={false} axisLine={false} domain={[0, 100]} tickFormatter={(v) => `${v}%`} />
          <Tooltip contentStyle={TOOLTIP} formatter={(v) => (v === null ? "—" : `${Number(v).toFixed(1)}%`)} />
          <Line type="monotone" dataKey="rate" name="Success %" stroke={LOCAL} strokeWidth={2} dot={false} connectNulls />
        </LineChart>
      </ResponsiveContainer>
    </Panel>
  );
}

export function TtftChart({ perDay }: { perDay: DayRow[] }) {
  const data = perDay.map((d) => ({ label: dayLabel(d.day), ttft: d.avg_ttft_ms }));
  return (
    <Panel title="First-token latency" desc="Average time to first token per day">
      <ResponsiveContainer width="100%" height={220}>
        <LineChart data={data} margin={{ left: -8, right: 8 }}>
          <CartesianGrid stroke={GRID} vertical={false} />
          <XAxis dataKey="label" tick={TICK} tickLine={false} axisLine={false} minTickGap={24} />
          <YAxis tick={TICK} tickLine={false} axisLine={false} tickFormatter={(v) => `${Math.round(Number(v) / 100) / 10}k`} />
          <Tooltip contentStyle={TOOLTIP} formatter={(v) => (v === null ? "—" : `${Number(v).toLocaleString()} ms`)} />
          <Line type="monotone" dataKey="ttft" name="First token (ms)" stroke={VIOLET} strokeWidth={2} dot={false} connectNulls />
        </LineChart>
      </ResponsiveContainer>
    </Panel>
  );
}

export function LocalCloudChats({ perDay }: { perDay: DayRow[] }) {  const data = perDay.map((d) => ({ label: dayLabel(d.day), Local: d.local_chats, Cloud: d.cloud_chats }));
  return (
    <Panel title="Local vs cloud chats" desc="Daily completions by engine">
      <ResponsiveContainer width="100%" height={220}>
        <BarChart data={data} margin={{ left: -8, right: 8 }}>
          <CartesianGrid stroke={GRID} vertical={false} />
          <XAxis dataKey="label" tick={TICK} tickLine={false} axisLine={false} minTickGap={24} />
          <YAxis tick={TICK} tickLine={false} axisLine={false} allowDecimals={false} />
          <Tooltip contentStyle={TOOLTIP} />
          <Legend />
            <Bar dataKey="Local" stackId="c" fill={LOCAL} />
            <Bar dataKey="Cloud" stackId="c" fill={CLOUD} radius={[4, 4, 0, 0]} />
          </BarChart>
        </ResponsiveContainer>
      </Panel>
    );
  }

export function ModelSpeedChart({ models }: { models: ModelRow[] }) {
  const data = models
    .filter((m) => m.avg_latency_ms !== null)
    .slice(0, 10)
    .map((m) => ({ name: m.model_name.length > 16 ? `${m.model_name.slice(0, 15)}…` : m.model_name, ms: m.avg_latency_ms ?? 0 }));
  return (
    <Panel title="Model speed" desc="Average generation latency (all time)">
      {data.length > 0 ? (
        <ResponsiveContainer width="100%" height={220}>
          <BarChart data={data} layout="vertical" margin={{ left: 8, right: 16 }}>
            <CartesianGrid stroke={GRID} horizontal={false} />
            <XAxis type="number" tick={TICK} tickLine={false} axisLine={false} tickFormatter={(v) => `${Math.round(Number(v) / 100) / 10}k`} />
            <YAxis type="category" dataKey="name" tick={TICK} tickLine={false} axisLine={false} width={120} />
            <Tooltip contentStyle={TOOLTIP} formatter={(v) => `${Number(v).toLocaleString()} ms`} />
            <Bar dataKey="ms" name="Avg ms" fill={LOCAL} radius={[0, 4, 4, 0]} />
          </BarChart>
        </ResponsiveContainer>
      ) : (
        <Empty what="latency samples" />
      )}
    </Panel>
  );
}

export function ModelErrorChart({ models }: { models: ModelRow[] }) {
  const data = models
    .filter((m) => m.failed > 0)
    .slice(0, 10)
    .map((m) => ({ name: m.model_name.length > 16 ? `${m.model_name.slice(0, 15)}…` : m.model_name, failed: m.failed }));
  return (
    <Panel title="Model errors" desc="Failed generations by model (all time)">
      {data.length > 0 ? (
        <ResponsiveContainer width="100%" height={220}>
          <BarChart data={data} layout="vertical" margin={{ left: 8, right: 16 }}>
            <CartesianGrid stroke={GRID} horizontal={false} />
            <XAxis type="number" tick={TICK} tickLine={false} axisLine={false} allowDecimals={false} />
            <YAxis type="category" dataKey="name" tick={TICK} tickLine={false} axisLine={false} width={120} />
            <Tooltip contentStyle={TOOLTIP} />
            <Bar dataKey="failed" name="Failures" fill={ROSE} radius={[0, 4, 4, 0]} />
          </BarChart>
        </ResponsiveContainer>
      ) : (
        <Empty what="model failures" />
      )}
    </Panel>
  );
}
