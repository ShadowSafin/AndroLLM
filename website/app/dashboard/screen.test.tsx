import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { DashboardScreen } from "@/app/dashboard/screen";

vi.mock("next/link", async () => {
  const React = await import("react");
  return {
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    default: ({ children, href }: any) =>
      React.createElement("a", { href: typeof href === "string" ? href : "#" }, children),
  };
});

const userHook = vi.hoisted(() => ({ mock: vi.fn() }));
const dashHook = vi.hoisted(() => ({ mock: vi.fn() }));

vi.mock("@/hooks/use-backend-user", () => ({ useBackendUser: userHook.mock }));
vi.mock("@/hooks/use-dashboard", () => ({ useDashboard: dashHook.mock }));

const signedIn = {
  firebaseUser: { email: "a@example.com" },
  profile: { web_connected: true, web_connected_at: "2026-09-20T00:00:00.000Z" },
  loading: false,
  error: null,
  refresh: async () => {},
};

describe("DashboardScreen states", () => {
  it("shows the connect prompt for unlinked accounts (no charts)", () => {
    userHook.mock.mockReturnValue({ ...signedIn, profile: { web_connected: false } });
    dashHook.mock.mockReturnValue({ status: "not-connected" });
    render(<DashboardScreen />);
    expect(screen.getByTestId("dashboard-not-connected")).toBeDefined();
    expect(screen.getAllByText(/Connect your Android app/i).length).toBeGreaterThan(0);
    expect(screen.queryByTestId("dashboard-charts")).toBeNull();
    expect(screen.queryByTestId("kpi-grid")).toBeNull();
  });

  it("shows the empty onboarding state for linked accounts without usage", () => {
    userHook.mock.mockReturnValue(signedIn);
    dashHook.mock.mockReturnValue({ status: "empty", refresh: async () => {} });
    render(<DashboardScreen />);
    expect(screen.getByTestId("dashboard-empty")).toBeDefined();
    expect(screen.getByText(/No analytics yet/i)).toBeDefined();
  });

  it("renders cards and activity for linked accounts with data", () => {
    userHook.mock.mockReturnValue(signedIn);
    dashHook.mock.mockReturnValue({
      status: "ready",
      summary: {
        totals: {
          chats: 3, events: 3, total_tokens: 800, input_tokens: 250, output_tokens: 550,
          local_tokens: 600, cloud_tokens: 200, sessions: 1,
          avg_latency_ms: 1667, avg_ttft_ms: 400, failed: 1,
        },
      },
      charts: {
        per_day: [
          { day: "2026-09-21", chats: 3, events: 3, input_tokens: 250, output_tokens: 550, total_tokens: 800, local_tokens: 600, cloud_tokens: 200, local_chats: 2, cloud_chats: 1, successes: 2, sessions: 1, errors: 1, avg_latency_ms: 1667, avg_ttft_ms: 400 },
        ],
        models: [
          { model_name: "qwen3-0.6b", provider_name: null, engine_type: "local", chats: 2, events: 2, total_tokens: 600, avg_latency_ms: 1000, failed: 0 },
        ],
        heatmap: [],
        providers: [],
        by_device: [],
        app_versions: [],
        token_histogram: [],
        session_durations: [],
      },
      activity: {
        events: [
          { event_id: "e1", event_type: "chat_completed", model_name: "qwen3-0.6b", provider_name: null, engine_type: "local", total_tokens: 300, latency_ms: 1000, success: true, error_code: null, device_name: "Pixel", created_at: "2026-09-21T13:00:00.000Z" },
        ],
        sessions: [],
        devices: [
          { id: "d1", device_identifier: "dev1", device_name: "Pixel", platform: "android", app_version: "1.1.6", last_seen_at: "2026-09-21T13:00:00.000Z" },
        ],
      },
      refresh: async () => {},
    });
    render(<DashboardScreen />);
    expect(screen.getByTestId("dashboard-ready")).toBeDefined();
    expect(screen.getByTestId("kpi-grid")).toBeDefined();
    expect(screen.getByTestId("dashboard-charts")).toBeDefined();
    expect(screen.getAllByText("qwen3-0.6b").length).toBeGreaterThan(0);
    fireEvent.click(screen.getByRole("tab", { name: "Activity" }));
    expect(screen.getByTestId("dashboard-activity")).toBeDefined();
    fireEvent.click(screen.getByRole("tab", { name: "Models" }));
    expect(screen.getByText("Model leaderboard")).toBeDefined();
  });
});
