"use client";

/**
 * Phase 4 expanded — ranked leaderboards (models, devices, sessions, errors).
 * Personal rows only; empty states per table.
 */
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { formatCompact, formatDuration, formatMs } from "@/lib/dashboard-format";
import type {
  ActivityEvent,
  ActivitySession,
  DeviceUsageRow,
  ModelRow,
} from "@/lib/dashboard-client";

function Table({
  head,
  rows,
  empty,
}: {
  head: string[];
  rows: React.ReactNode[][];
  empty: string;
}) {
  if (rows.length === 0) return <p className="py-8 text-center text-sm text-gray-500">{empty}</p>;
  return (
    <div className="overflow-x-auto">
      <table className="w-full text-left text-sm">
        <thead>
          <tr className="border-b border-white/[0.06] text-xs uppercase tracking-wider text-gray-500">
            {head.map((h, i) => (
              <th key={h} className={`px-3 py-2 font-medium ${i > 0 ? "text-right" : ""}`}>
                {h}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((cells, r) => (
            <tr key={r} className="border-b border-white/[0.04] last:border-0">
              {cells.map((c, i) => (
                <td key={i} className={`px-3 py-2.5 text-gray-200 ${i > 0 ? "text-right tabular-nums" : ""}`}>
                  {c}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
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

export function ModelLeaderboard({ models }: { models: ModelRow[] }) {
  return (
    <Panel title="Model leaderboard" desc="Ranked by tokens · latency and failures inline">
      <Table
        head={["#", "Model", "Chats", "Tokens", "Avg latency", "Failed"]}
        empty="No model usage in this window."
        rows={models.slice(0, 10).map((m, i) => [
          <span key="r" className="text-gray-500">{i + 1}</span>,
          <span key="m" className="font-medium">
            {m.model_name}
            <span className="ml-2 text-xs text-gray-500">{m.engine_type ?? ""}{m.provider_name ? ` · ${m.provider_name}` : ""}</span>
          </span>,
          formatCompact(m.chats),
          formatCompact(m.total_tokens),
          formatMs(m.avg_latency_ms),
          m.failed > 0 ? <span key="f" className="text-rose-400">{m.failed}</span> : "0",
        ])}
      />
    </Panel>
  );
}

export function DeviceLeaderboard({ rows }: { rows: DeviceUsageRow[] }) {
  return (
    <Panel title="Device leaderboard" desc="Usage and sessions per linked device">
      <Table
        head={["Device", "App", "Chats", "Tokens", "Sessions"]}
        empty="No devices yet."
        rows={rows.map((d) => [
          <span key="d" className="font-medium">
            {d.device_name ?? "Unknown device"}
            <span className="ml-2 text-xs text-gray-500">{d.platform}</span>
          </span>,
          <span key="v" className="text-gray-400">v{d.app_version ?? "?"}</span>,
          formatCompact(d.chats),
          formatCompact(d.total_tokens),
          formatCompact(d.sessions),
        ])}
      />
    </Panel>
  );
}

export function SessionLeaderboard({ sessions }: { sessions: ActivitySession[] }) {
  const ranked = [...sessions]
    .map((s) => ({
      s,
      secs: s.ended_at ? Math.max(0, (new Date(s.ended_at).getTime() - new Date(s.started_at).getTime()) / 1000) : null,
    }))
    .sort((a, b) => (b.secs ?? -1) - (a.secs ?? -1))
    .slice(0, 10);
  return (
    <Panel title="Session leaderboard" desc="Longest sessions first">
      <Table
        head={["Started", "Engine", "Events", "Duration"]}
        empty="No sessions yet."
        rows={ranked.map(({ s, secs }) => [
          <span key="t" className="text-gray-400">{new Date(s.started_at).toLocaleDateString("en-US", { month: "short", day: "numeric" })}</span>,
          s.engine_type ?? "—",
          formatCompact(s.events),
          secs === null ? <span key="a" className="text-emerald-400">Active</span> : formatDuration(secs),
        ])}
      />
    </Panel>
  );
}

export function ErrorLeaderboard({ events }: { events: ActivityEvent[] }) {
  const byCode = new Map<string, { count: number; model: string }>();
  for (const e of events) {
    if (e.success) continue;
    const code = e.error_code ?? "unknown";
    const cur = byCode.get(code) ?? { count: 0, model: e.model_name ?? "—" };
    cur.count++;
    byCode.set(code, cur);
  }
  const rows = [...byCode.entries()].sort((a, b) => b[1].count - a[1].count).slice(0, 10);
  return (
    <Panel title="Error leaderboard" desc="Most frequent failure codes (recent events)">
      <Table
        head={["Error", "Count", "Example model"]}
        empty="No failures in recent activity. Clean run."
        rows={rows.map(([code, v]) => [
          <span key="c" className="font-mono text-rose-300">{code}</span>,
          formatCompact(v.count),
          v.model,
        ])}
      />
    </Panel>
  );
}
