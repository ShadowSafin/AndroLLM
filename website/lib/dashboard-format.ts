/**
 * Phase 4 — pure presentation helpers for the dashboard (unit-tested).
 * No Firebase, no fetch, no backend shapes beyond plain numbers.
 */

/** Compact number: 950 → "950", 1500 → "1.5K", 2_500_000 → "2.5M". */
export function formatCompact(n: number): string {
  if (!Number.isFinite(n)) return "—";
  const abs = Math.abs(n);
  if (abs >= 1_000_000_000) return `${trim(n / 1_000_000_000)}B`;
  if (abs >= 1_000_000) return `${trim(n / 1_000_000)}M`;
  if (abs >= 1_000) return `${trim(n / 1_000)}K`;
  return String(Math.round(n));
}

function trim(v: number): string {
  const rounded = Math.round(v * 10) / 10;
  return Number.isInteger(rounded) ? String(rounded) : rounded.toFixed(1);
}

/** Milliseconds → "800 ms", "2.5 s", "1.2 min". Null-safe. */
export function formatMs(ms: number | null | undefined): string {
  if (ms === null || ms === undefined || !Number.isFinite(ms)) return "—";
  if (ms < 1000) return `${Math.round(ms)} ms`;
  if (ms < 60_000) return `${trim(ms / 1000)} s`;
  return `${trim(ms / 60_000)} min`;
}

/** "2026-09-21" → "Sep 21". Falls back to the raw string. */
export function dayLabel(isoDay: string): string {
  const d = new Date(`${isoDay}T00:00:00Z`);
  if (Number.isNaN(d.getTime())) return isoDay;
  return d.toLocaleDateString("en-US", { month: "short", day: "numeric", timeZone: "UTC" });
}

/** ISO timestamp → "Sep 21, 14:32" (local tz). Falls back to raw string. */
export function timeLabel(iso: string): string {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  return d.toLocaleDateString("en-US", { month: "short", day: "numeric" }) +
    ", " +
    d.toLocaleTimeString("en-US", { hour: "2-digit", minute: "2-digit" });
}

/** Failure share 0..1, null when there is nothing to divide by. */
export function failureRate(failed: number, total: number): number | null {
  if (!Number.isFinite(failed) || !Number.isFinite(total) || total <= 0) return null;
  return Math.min(1, Math.max(0, failed / total));
}

export function formatPercent(ratio: number | null): string {
  if (ratio === null) return "—";
  return `${(ratio * 100).toFixed(ratio < 0.1 && ratio > 0 ? 1 : 0)}%`;
}

/** True when every headline total is zero (connected but nothing synced yet). */
export function isEmptyTotals(t: { chats: number; events: number; total_tokens: number; sessions: number }): boolean {
  return t.chats === 0 && t.events === 0 && t.total_tokens === 0 && t.sessions === 0;
}

/** Local vs cloud token split for the donut (omits empty slices). */
export function engineSplit(localTokens: number, cloudTokens: number): { name: string; value: number }[] {
  const out: { name: string; value: number }[] = [];
  if (localTokens > 0) out.push({ name: "Local", value: localTokens });
  if (cloudTokens > 0) out.push({ name: "Cloud", value: cloudTokens });
  return out;
}

/** Seconds → "45s", "12m", "3.2h". Null-safe. */
export function formatDuration(seconds: number | null | undefined): string {
  if (seconds === null || seconds === undefined || !Number.isFinite(seconds)) return "—";
  if (seconds < 60) return `${Math.round(seconds)}s`;
  if (seconds < 3600) return `${trim(seconds / 60)}m`;
  return `${trim(seconds / 3600)}h`;
}

/** ISO timestamp → "2h ago", "3d ago", "just now". Falls back to "—". */
export function relativeTime(iso: string | null | undefined, nowMs = Date.now()): string {
  if (!iso) return "—";
  const t = new Date(iso).getTime();
  if (Number.isNaN(t)) return "—";
  const diff = Math.max(0, nowMs - t);
  const mins = Math.floor(diff / 60_000);
  if (mins < 1) return "just now";
  if (mins < 60) return `${mins}m ago`;
  const hours = Math.floor(mins / 60);
  if (hours < 24) return `${hours}h ago`;
  const days = Math.floor(hours / 24);
  if (days < 30) return `${days}d ago`;
  return `${Math.floor(days / 30)}mo ago`;
}

export type RangeKey = 7 | 30 | 90;

/** Last N rows of a day series (oldest first). */
export function sliceRange<T extends { day: string }>(rows: T[], range: RangeKey): T[] {
  return rows.slice(Math.max(0, rows.length - range));
}

export function sumDays<T>(rows: T[], pick: (r: T) => number): number {
  return rows.reduce((n, r) => n + pick(r), 0);
}

export type WeekBucket = {
  label: string;
  chats: number;
  tokens: number;
  sessions: number;
  errors: number;
};

/** Group a day series into ISO-ish calendar weeks (oldest first). */
export function groupByWeek<T extends { day: string; chats: number; total_tokens: number; sessions: number; errors: number }>(
  rows: T[],
): WeekBucket[] {
  const weeks: WeekBucket[] = [];
  let curKey = "";
  for (const r of rows) {
    const d = new Date(`${r.day}T00:00:00Z`);
    if (Number.isNaN(d.getTime())) continue;
    const monday = new Date(d);
    monday.setUTCDate(d.getUTCDate() - ((d.getUTCDay() + 6) % 7));
    const key = monday.toISOString().slice(0, 10);
    if (key !== curKey || weeks.length === 0) {
      weeks.push({ label: dayLabel(key), chats: 0, tokens: 0, sessions: 0, errors: 0 });
      curKey = key;
    }
    const cur = weeks[weeks.length - 1];
    cur.chats += r.chats;
    cur.tokens += r.total_tokens;
    cur.sessions += r.sessions;
    cur.errors += r.errors;
  }
  return weeks;
}

/** Percent change current vs previous (null when previous is 0/empty). */
export function growthRate(current: number, previous: number): number | null {
  if (!Number.isFinite(current) || !Number.isFinite(previous) || previous <= 0) return null;
  return (current - previous) / previous;
}

export function formatGrowth(ratio: number | null): { text: string; up: boolean | null } {
  if (ratio === null) return { text: "—", up: null };
  const pct = `${ratio >= 0 ? "+" : ""}${(ratio * 100).toFixed(1)}%`;
  return { text: pct, up: ratio >= 0 };
}

/** Consecutive active (chats > 0) days ending today/yesterday. */
export function currentStreak<T extends { day: string; chats: number }>(rows: T[], todayIso?: string): number {
  const today = (todayIso ?? new Date().toISOString().slice(0, 10)).slice(0, 10);
  const byDay = new Map(rows.map((r) => [r.day.slice(0, 10), r.chats]));
  let streak = 0;
  const cursor = new Date(`${today}T00:00:00Z`);
  if ((byDay.get(today) ?? 0) === 0) cursor.setUTCDate(cursor.getUTCDate() - 1);
  for (;;) {
    const key = cursor.toISOString().slice(0, 10);
    if ((byDay.get(key) ?? 0) > 0) {
      streak++;
      cursor.setUTCDate(cursor.getUTCDate() - 1);
    } else break;
  }
  return streak;
}

const DOW_NAMES = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"];

export function dowName(dow: number): string {
  return DOW_NAMES[((dow % 7) + 7) % 7];
}

export function hourLabel(hour: number): string {
  const h = ((hour % 24) + 24) % 24;
  if (h === 0) return "12a";
  if (h === 12) return "12p";
  return h < 12 ? `${h}a` : `${h - 12}p`;
}

/** Weekday (Mon–Fri) vs weekend chats/tokens from heat cells. */
export function weekdaySplit(cells: { dow: number; chats: number; tokens: number }[]): {
  weekday: { chats: number; tokens: number };
  weekend: { chats: number; tokens: number };
} {
  const weekday = { chats: 0, tokens: 0 };
  const weekend = { chats: 0, tokens: 0 };
  for (const c of cells) {
    const target = c.dow === 0 || c.dow === 6 ? weekend : weekday;
    target.chats += c.chats;
    target.tokens += c.tokens;
  }
  return { weekday, weekend };
}

/** Peak (top-6 active hours) vs off-hours chats from heat cells. */
export function peakSplit(cells: { hour: number; chats: number }[]): {
  peak: { hours: number[]; chats: number };
  off: { chats: number };
} {
  const byHour = new Map<number, number>();
  let total = 0;
  for (const c of cells) {
    byHour.set(c.hour, (byHour.get(c.hour) ?? 0) + c.chats);
    total += c.chats;
  }
  const ranked = [...byHour.entries()].sort((a, b) => b[1] - a[1]);
  const peakHours = ranked.slice(0, 6).map(([h]) => h).sort((a, b) => a - b);
  const peakChats = ranked.slice(0, 6).reduce((n, [, c]) => n + c, 0);
  return { peak: { hours: peakHours, chats: peakChats }, off: { chats: total - peakChats } };
}

export const TOKEN_BUCKETS = ["0-100", "100-500", "500-1k", "1k-2k", "2k-4k", "4k-8k", "8k+"];
export const DURATION_BUCKETS = ["<1m", "1-5m", "5-15m", "15-60m", ">60m"];

/** Fill missing buckets with zeros in canonical order. */
export function orderBuckets(counts: { bucket: string; count: number }[], order: string[]): { bucket: string; count: number }[] {
  const byBucket = new Map(counts.map((c) => [c.bucket, c.count]));
  return order.map((bucket) => ({ bucket, count: byBucket.get(bucket) ?? 0 }));
}
