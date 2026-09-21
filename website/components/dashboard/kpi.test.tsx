import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { KpiGrid } from "./kpi";
import type { SummaryTotals } from "@/lib/dashboard-client";

const totals: SummaryTotals = {
  chats: 12,
  events: 15,
  total_tokens: 2500,
  input_tokens: 1000,
  output_tokens: 1500,
  local_tokens: 2000,
  cloud_tokens: 500,
  sessions: 4,
  avg_latency_ms: 2500,
  avg_ttft_ms: 800,
  failed: 1,
  devices: 1,
  models_count: 2,
  longest_session_seconds: 900,
  peak_day: { day: "2026-09-21", chats: 5 },
  active_days: 3,
  last_event_at: "2026-09-21T13:00:00.000Z",
  last_synced_at: "2026-09-21T13:01:00.000Z",
  by_source: [{ source_page: "cloud_usage_dashboard", events: 15 }],
};

const perDay = [
  { day: "2026-09-20", chats: 5, events: 6, input_tokens: 400, output_tokens: 600, total_tokens: 1000, local_tokens: 800, cloud_tokens: 200, local_chats: 4, cloud_chats: 1, successes: 6, sessions: 2, errors: 0, avg_latency_ms: 2000, avg_ttft_ms: 700 },
  { day: "2026-09-21", chats: 7, events: 9, input_tokens: 600, output_tokens: 900, total_tokens: 1500, local_tokens: 1200, cloud_tokens: 300, local_chats: 5, cloud_chats: 2, successes: 8, sessions: 2, errors: 1, avg_latency_ms: 3000, avg_ttft_ms: 900 },
];

const models = [
  { model_name: "qwen3-0.6b", provider_name: null, engine_type: "local", chats: 9, events: 11, total_tokens: 2000, avg_latency_ms: 2500, failed: 0 },
];

describe("KpiGrid", () => {
  it("renders the expanded headline cards", () => {
    render(<KpiGrid totals={totals} perDay={perDay} models={models} />);
    expect(screen.getByTestId("kpi-grid")).toBeDefined();
    expect(screen.getByText("Total chats")).toBeDefined();
    expect(screen.getByText("12")).toBeDefined();
    expect(screen.getByText("2.5K")).toBeDefined();
    expect(screen.getByText("Success rate")).toBeDefined();
    expect(screen.getByText("Day streak")).toBeDefined();
    expect(screen.getByText("Weekly growth")).toBeDefined();
    expect(screen.getByText("Monthly growth")).toBeDefined();
  });
});
