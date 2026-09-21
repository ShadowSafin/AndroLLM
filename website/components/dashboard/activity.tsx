"use client";

/**
 * Phase 4 — recent activity, sessions, and devices (signed-in user only).
 */
import { CheckCircle2, Cpu, Smartphone, XCircle } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { formatCompact, formatMs, timeLabel } from "@/lib/dashboard-format";
import type { DashboardActivity } from "@/lib/dashboard-client";

function Section({ title, desc, children }: { title: string; desc: string; children: React.ReactNode }) {
  return (
    <Card>
      <CardHeader>
        <CardTitle>{title}</CardTitle>
        <CardDescription>{desc}</CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-2">{children}</CardContent>
    </Card>
  );
}

export function ActivityView({ activity }: { activity: DashboardActivity }) {
  return (
    <div className="grid grid-cols-1 gap-3 lg:grid-cols-2" data-testid="dashboard-activity">
      <Section title="Recent activity" desc="Latest synced generations">
        {activity.events.length === 0 ? (
          <p className="py-8 text-center text-sm text-gray-500">No activity yet.</p>
        ) : (
          activity.events.map((e) => (
            <div
              key={e.event_id}
              className="flex items-center gap-3 rounded-xl border border-white/[0.06] bg-white/[0.02] px-4 py-3"
            >
              {e.success ? (
                <CheckCircle2 className="size-4 shrink-0 text-emerald-400" aria-label="succeeded" />
              ) : (
                <XCircle className="size-4 shrink-0 text-rose-400" aria-label="failed" />
              )}
              <div className="min-w-0 flex-1">
                <p className="truncate text-sm font-medium text-gray-100">
                  {e.model_name ?? e.event_type}
                  {e.error_code ? <span className="ml-2 text-xs text-rose-400">{e.error_code}</span> : null}
                </p>
                <p className="truncate text-xs text-gray-500">
                  {formatCompact(e.total_tokens)} tokens
                  {e.latency_ms !== null ? ` · ${formatMs(e.latency_ms)}` : ""}
                  {e.device_name ? ` · ${e.device_name}` : ""}
                </p>
              </div>
              <span className="shrink-0 text-xs text-gray-500">{timeLabel(e.created_at)}</span>
            </div>
          ))
        )}
      </Section>

      <div className="flex flex-col gap-3">
        <Section title="Sessions" desc="Recent chat sessions">
          {activity.sessions.length === 0 ? (
            <p className="py-8 text-center text-sm text-gray-500">No sessions yet.</p>
          ) : (
            activity.sessions.slice(0, 6).map((s) => (
              <div
                key={s.id}
                className="flex items-center gap-3 rounded-xl border border-white/[0.06] bg-white/[0.02] px-4 py-3"
              >
                <Cpu className="size-4 shrink-0 text-gray-400" aria-hidden />
                <div className="min-w-0 flex-1">
                  <p className="truncate text-sm font-medium text-gray-100">
                    {s.engine_type ?? "session"} · {s.events} event{s.events === 1 ? "" : "s"}
                  </p>
                  <p className="truncate text-xs text-gray-500">
                    {timeLabel(s.started_at)}
                    {s.device_name ? ` · ${s.device_name}` : ""}
                  </p>
                </div>
                <Badge variant={s.ended_at ? "secondary" : "ember"}>
                  {s.ended_at ? "Ended" : "Active"}
                </Badge>
              </div>
            ))
          )}
        </Section>

        <Section title="Devices" desc="Linked devices syncing to this account">
          {activity.devices.length === 0 ? (
            <p className="py-8 text-center text-sm text-gray-500">No devices yet.</p>
          ) : (
            activity.devices.map((d) => (
              <div
                key={d.id}
                className="flex items-center gap-3 rounded-xl border border-white/[0.06] bg-white/[0.02] px-4 py-3"
              >
                <Smartphone className="size-4 shrink-0 text-gray-400" aria-hidden />
                <div className="min-w-0 flex-1">
                  <p className="truncate text-sm font-medium text-gray-100">
                    {d.device_name ?? d.device_identifier}
                  </p>
                  <p className="truncate text-xs text-gray-500">
                    {d.platform}
                    {d.app_version ? ` · v${d.app_version}` : ""} · seen {timeLabel(d.last_seen_at)}
                  </p>
                </div>
                <Badge variant="secondary">Linked</Badge>
              </div>
            ))
          )}
        </Section>
      </div>
    </div>
  );
}
