/**
 * Phase 4 — typed client for the personal dashboard endpoints.
 *
 * Same auth model as backend-client.ts: Firebase ID token attached per
 * request, identity derived server-side from the verified token. These
 * endpoints return ONLY the signed-in user's rows (403 web_not_connected
 * when the Android app was never linked).
 */
import { apiGet, BackendError } from "./backend-client";

export type SummaryTotals = {
  chats: number;
  events: number;
  total_tokens: number;
  input_tokens: number;
  output_tokens: number;
  local_tokens: number;
  cloud_tokens: number;
  sessions: number;
  avg_latency_ms: number | null;
  avg_ttft_ms: number | null;
  failed: number;
  devices: number;
  models_count: number;
  longest_session_seconds: number | null;
  peak_day: { day: string; chats: number } | null;
  active_days: number;
  last_event_at: string | null;
  last_synced_at: string | null;
  by_source: { source_page: string | null; events: number }[];
};

export type DayRow = {
  day: string;
  chats: number;
  events: number;
  input_tokens: number;
  output_tokens: number;
  total_tokens: number;
  local_tokens: number;
  cloud_tokens: number;
  local_chats: number;
  cloud_chats: number;
  successes: number;
  sessions: number;
  errors: number;
  avg_latency_ms: number | null;
  avg_ttft_ms: number | null;
};

export type ModelRow = {
  model_name: string;
  provider_name: string | null;
  engine_type: string | null;
  chats: number;
  events: number;
  total_tokens: number;
  avg_latency_ms: number | null;
  failed: number;
};

export type ActivityEvent = {
  event_id: string;
  event_type: string;
  model_name: string | null;
  provider_name: string | null;
  engine_type: string | null;
  total_tokens: number;
  latency_ms: number | null;
  success: boolean;
  error_code: string | null;
  device_name: string | null;
  created_at: string;
};

export type ActivitySession = {
  id: string;
  started_at: string;
  ended_at: string | null;
  engine_type: string | null;
  device_name: string | null;
  events: number;
};

export type ActivityDevice = {
  id: string;
  device_identifier: string;
  device_name: string | null;
  platform: string;
  app_version: string | null;
  last_seen_at: string;
};

export type DashboardSummary = { totals: SummaryTotals };

export type HeatCell = { dow: number; hour: number; chats: number; tokens: number };

export type ProviderRow = {
  provider_name: string;
  chats: number;
  events: number;
  total_tokens: number;
  avg_latency_ms: number | null;
  failed: number;
};

export type DeviceUsageRow = {
  device_name: string | null;
  app_version: string | null;
  platform: string;
  chats: number;
  events: number;
  total_tokens: number;
  sessions: number;
};

export type AppVersionRow = {
  app_version: string;
  devices: number;
  chats: number;
  total_tokens: number;
};

export type BucketCount = { bucket: string; count: number };

export type DashboardCharts = {
  per_day: DayRow[];
  models: ModelRow[];
  heatmap: HeatCell[];
  providers: ProviderRow[];
  by_device: DeviceUsageRow[];
  app_versions: AppVersionRow[];
  token_histogram: BucketCount[];
  session_durations: BucketCount[];
};
export type DashboardActivity = {
  events: ActivityEvent[];
  sessions: ActivitySession[];
  devices: ActivityDevice[];
};

/** True when the backend says this account never linked the Android app. */
export function isNotConnectedError(e: unknown): boolean {
  return e instanceof BackendError && e.status === 403;
}

export async function fetchDashboardSummary(): Promise<DashboardSummary> {
  return apiGet<DashboardSummary>("/dashboard/summary");
}

export async function fetchDashboardCharts(days = 30): Promise<DashboardCharts> {
  return apiGet<DashboardCharts>(`/dashboard/charts?days=${days}`);
}

export async function fetchDashboardActivity(limit = 20): Promise<DashboardActivity> {
  return apiGet<DashboardActivity>(`/dashboard/activity?limit=${limit}`);
}
