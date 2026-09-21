import { beforeEach, describe, expect, it, vi } from "vitest";
import { BackendError } from "./backend-client";
import {
  fetchDashboardActivity,
  fetchDashboardCharts,
  fetchDashboardSummary,
  isNotConnectedError,
} from "./dashboard-client";
import { getIdToken } from "./firebase-client";

vi.mock("./firebase-client", () => ({
  getIdToken: vi.fn(),
}));

const tokenMock = vi.mocked(getIdToken);

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

describe("dashboard client auth", () => {
  beforeEach(() => {
    vi.unstubAllGlobals();
    vi.stubGlobal("fetch", vi.fn());
    process.env.NEXT_PUBLIC_BACKEND_URL = "https://api.example.test";
    tokenMock.mockResolvedValue("test-id-token");
  });

  it("attaches the Firebase ID token to every dashboard call", async () => {
    const fetchMock = vi.mocked(fetch);
    fetchMock.mockResolvedValueOnce(jsonResponse({ totals: {} }));
    fetchMock.mockResolvedValueOnce(jsonResponse({ per_day: [], models: [] }));
    fetchMock.mockResolvedValueOnce(jsonResponse({ events: [], sessions: [], devices: [] }));

    await fetchDashboardSummary();
    await fetchDashboardCharts(30);
    await fetchDashboardActivity(20);

    expect(fetchMock).toHaveBeenCalledTimes(3);
    for (const call of fetchMock.mock.calls) {
      const [url, init] = call as [string, RequestInit];
      expect(url.startsWith("https://api.example.test/dashboard/")).toBe(true);
      expect((init.headers as Record<string, string>).Authorization).toBe("Bearer test-id-token");
    }
  });

  it("maps 403 to the not-connected signal", async () => {
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse({ error: "web_not_connected" }, 403));
    const err = await fetchDashboardSummary().catch((e) => e);
    expect(err).toBeInstanceOf(BackendError);
    expect((err as BackendError).status).toBe(403);
    expect(isNotConnectedError(err)).toBe(true);
    expect(isNotConnectedError(new Error("nope"))).toBe(false);
  });

  it("surfaces auth failures clearly", async () => {
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse({ error: "unauthorized" }, 401));
    const err = await fetchDashboardCharts().catch((e) => e);
    expect(err).toBeInstanceOf(BackendError);
    expect(isNotConnectedError(err)).toBe(false);
  });
});
