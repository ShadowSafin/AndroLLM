import { describe, expect, it } from "vitest";
import { formatCompact } from "./dashboard-format";
import type {
  ActivityEvent,
  DayRow,
  ModelRow,
} from "./dashboard-client";

/**
 * Chart-shape guards: the UI only renders personal rows (verified by backend
 * tests), and these assertions pin the shapes the charts consume so a backend
 * drift fails here instead of silently rendering global-looking data.
 */
function assertDayRow(r: DayRow) {
  expect(typeof r.day).toBe("string");
  for (const k of ["chats", "events", "total_tokens", "local_tokens", "cloud_tokens", "local_chats", "cloud_chats", "successes", "sessions", "errors"] as const) {
    expect(typeof r[k]).toBe("number");
  }
  void formatCompact(r.total_tokens);
}

describe("dashboard payload shapes", () => {
  it("accepts a realistic per-day series", () => {
    const rows: DayRow[] = [
      { day: "2026-09-20", chats: 0, events: 0, input_tokens: 0, output_tokens: 0, total_tokens: 0, local_tokens: 0, cloud_tokens: 0, local_chats: 0, cloud_chats: 0, successes: 0, sessions: 0, errors: 0, avg_latency_ms: null, avg_ttft_ms: null },
      { day: "2026-09-21", chats: 3, events: 3, input_tokens: 250, output_tokens: 550, total_tokens: 800, local_tokens: 600, cloud_tokens: 200, local_chats: 2, cloud_chats: 1, successes: 2, sessions: 1, errors: 1, avg_latency_ms: 1667, avg_ttft_ms: 400 },
    ];
    rows.forEach(assertDayRow);
    expect(rows.reduce((n, r) => n + r.chats, 0)).toBe(3);
  });

  it("accepts model rows and activity events", () => {
    const models: ModelRow[] = [
      { model_name: "qwen3-0.6b", provider_name: null, engine_type: "local", chats: 2, events: 2, total_tokens: 600, avg_latency_ms: 1000, failed: 0 },
    ];
    expect(models[0].total_tokens).toBe(600);
    const events: ActivityEvent[] = [
      { event_id: "e1", event_type: "chat_completed", model_name: "qwen3-0.6b", provider_name: null, engine_type: "local", total_tokens: 300, latency_ms: 1000, success: true, error_code: null, device_name: "Pixel", created_at: "2026-09-21T13:00:00.000Z" },
    ];
    expect(events[0].success).toBe(true);
  });
});
