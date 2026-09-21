"use client";

/**
 * Phase 4 — personal usage charts (Recharts, dark theme).
 * All series come from the UID-scoped backend endpoints; nothing global.
 */
import {
  Area,
  AreaChart,
  Bar,
  BarChart,
  CartesianGrid,
  Cell,
  Legend,
  Line,
  LineChart,
  Pie,
  PieChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { dayLabel, engineSplit, formatCompact } from "@/lib/dashboard-format";
import type { DayRow, ModelRow } from "@/lib/dashboard-client";

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

export function DashboardChartsView({ charts }: { charts: { per_day: DayRow[]; models: ModelRow[] } }) {
  const perDay = charts.per_day.map((d) => ({
    ...d,
    label: dayLabel(d.day),
    failureRate: d.events > 0 ? d.errors / d.events : 0,
  }));
  const split = engineSplit(
    charts.per_day.reduce((n, d) => n + d.local_tokens, 0),
    charts.per_day.reduce((n, d) => n + d.cloud_tokens, 0),
  );
  const models = charts.models.slice(0, 8).map((m) => ({
    name: m.model_name.length > 18 ? `${m.model_name.slice(0, 17)}…` : m.model_name,
    tokens: m.total_tokens,
    chats: m.chats,
  }));

  return (
    <div className="grid grid-cols-1 gap-3 lg:grid-cols-2" data-testid="dashboard-charts">
      <Panel title="Tokens per day" desc="Local vs cloud generation volume">
        <ResponsiveContainer width="100%" height={260}>
          <AreaChart data={perDay} margin={{ left: -8, right: 8 }}>
            <CartesianGrid stroke={GRID} vertical={false} />
            <XAxis dataKey="label" tick={TICK} tickLine={false} axisLine={false} minTickGap={24} />
            <YAxis tick={TICK} tickLine={false} axisLine={false} tickFormatter={formatCompact} />
            <Tooltip contentStyle={TOOLTIP} formatter={(v) => formatCompact(Number(v))} />
            <Legend />
            <Area type="monotone" dataKey="local_tokens" name="Local" stackId="t" stroke={LOCAL} fill={LOCAL} fillOpacity={0.35} />
            <Area type="monotone" dataKey="cloud_tokens" name="Cloud" stackId="t" stroke={CLOUD} fill={CLOUD} fillOpacity={0.35} />
          </AreaChart>
        </ResponsiveContainer>
      </Panel>

      <Panel title="Chats & sessions" desc="Daily completions and sessions started">
        <ResponsiveContainer width="100%" height={260}>
          <BarChart data={perDay} margin={{ left: -8, right: 8 }}>
            <CartesianGrid stroke={GRID} vertical={false} />
            <XAxis dataKey="label" tick={TICK} tickLine={false} axisLine={false} minTickGap={24} />
            <YAxis tick={TICK} tickLine={false} axisLine={false} allowDecimals={false} />
            <Tooltip contentStyle={TOOLTIP} />
            <Legend />
            <Bar dataKey="chats" name="Chats" fill={ACCENT} radius={[4, 4, 0, 0]} />
            <Bar dataKey="sessions" name="Sessions" fill="#a78bfa" radius={[4, 4, 0, 0]} />
          </BarChart>
        </ResponsiveContainer>
      </Panel>

      <Panel title="Local vs cloud" desc="Token share in the selected window">
        {split.length > 0 ? (
          <ResponsiveContainer width="100%" height={260}>
            <PieChart>
              <Pie data={split} dataKey="value" nameKey="name" innerRadius={64} outerRadius={96} paddingAngle={3}>
                {split.map((s) => (
                  <Cell key={s.name} fill={s.name === "Local" ? LOCAL : CLOUD} />
                ))}
              </Pie>
              <Tooltip contentStyle={TOOLTIP} formatter={(v) => formatCompact(Number(v))} />
              <Legend />
            </PieChart>
          </ResponsiveContainer>
        ) : (
          <p className="py-16 text-center text-sm text-gray-500">No token volume in this window.</p>
        )}
      </Panel>

      <Panel title="Top models" desc="Most-used models by tokens">
        {models.length > 0 ? (
          <ResponsiveContainer width="100%" height={260}>
            <BarChart data={models} layout="vertical" margin={{ left: 8, right: 16 }}>
              <CartesianGrid stroke={GRID} horizontal={false} />
              <XAxis type="number" tick={TICK} tickLine={false} axisLine={false} tickFormatter={formatCompact} />
              <YAxis type="category" dataKey="name" tick={TICK} tickLine={false} axisLine={false} width={120} />
              <Tooltip contentStyle={TOOLTIP} formatter={(v) => formatCompact(Number(v))} />
              <Bar dataKey="tokens" name="Tokens" fill={ACCENT} radius={[0, 4, 4, 0]} />
            </BarChart>
          </ResponsiveContainer>
        ) : (
          <p className="py-16 text-center text-sm text-gray-500">No model usage in this window.</p>
        )}
      </Panel>

      <Panel title="Latency" desc="Average generation latency per day">
        <ResponsiveContainer width="100%" height={260}>
          <LineChart data={perDay} margin={{ left: -8, right: 8 }}>
            <CartesianGrid stroke={GRID} vertical={false} />
            <XAxis dataKey="label" tick={TICK} tickLine={false} axisLine={false} minTickGap={24} />
            <YAxis tick={TICK} tickLine={false} axisLine={false} tickFormatter={(v) => `${Math.round(Number(v) / 100) / 10}k`} />
            <Tooltip contentStyle={TOOLTIP} formatter={(v) => `${Number(v).toLocaleString()} ms`} />
            <Line type="monotone" dataKey="avg_latency_ms" name="Avg latency (ms)" stroke={LOCAL} strokeWidth={2} dot={false} connectNulls />
          </LineChart>
        </ResponsiveContainer>
      </Panel>

      <Panel title="Failures" desc="Failed generations per day">
        <ResponsiveContainer width="100%" height={260}>
          <BarChart data={perDay} margin={{ left: -8, right: 8 }}>
            <CartesianGrid stroke={GRID} vertical={false} />
            <XAxis dataKey="label" tick={TICK} tickLine={false} axisLine={false} minTickGap={24} />
            <YAxis tick={TICK} tickLine={false} axisLine={false} allowDecimals={false} />
            <Tooltip contentStyle={TOOLTIP} />
            <Bar dataKey="errors" name="Failures" fill={ROSE} radius={[4, 4, 0, 0]} />
          </BarChart>
        </ResponsiveContainer>
      </Panel>
    </div>
  );
}
