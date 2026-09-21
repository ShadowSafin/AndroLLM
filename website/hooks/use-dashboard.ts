"use client";

/**
 * Phase 4 — dashboard data orchestration.
 *
 * States: signed-out | not-connected (profile says so, or backend 403s) |
 * loading | error | empty (connected, zero usage) | ready.
 *
 * Data freshness: the app uploads ~8s after each generation (debounced
 * trigger), and this hook silently re-fetches every 10s while the tab is
 * visible — new usage appears on screen within ~10s of happening.
 */
import { useCallback, useEffect, useRef, useState } from "react";
import { BackendError } from "@/lib/backend-client";
import {
  fetchDashboardActivity,
  fetchDashboardCharts,
  fetchDashboardSummary,
  isNotConnectedError,
  type DashboardActivity,
  type DashboardCharts,
  type DashboardSummary,
} from "@/lib/dashboard-client";
import { isEmptyTotals } from "@/lib/dashboard-format";
import { useBackendUser } from "./use-backend-user";

export type DashboardStatus =
  | "signed-out"
  | "not-connected"
  | "loading"
  | "error"
  | "empty"
  | "ready";

export type DashboardState = {
  status: DashboardStatus;
  summary: DashboardSummary | null;
  charts: DashboardCharts | null;
  activity: DashboardActivity | null;
  error: string | null;
  refresh: () => Promise<void>;
};

export function useDashboard(): DashboardState {
  const { firebaseUser, profile, loading: userLoading } = useBackendUser();
  const [summary, setSummary] = useState<DashboardSummary | null>(null);
  const [charts, setCharts] = useState<DashboardCharts | null>(null);
  const [activity, setActivity] = useState<DashboardActivity | null>(null);
  const [status, setStatus] = useState<DashboardStatus>("loading");
  const [error, setError] = useState<string | null>(null);
  const statusRef = useRef(status);
  statusRef.current = status;

  // Always a 90-day window: ranges slice client-side, and growth/streaks
  // compare against the previous period inside the same payload.
  const days = 90;

  const fetchAll = useCallback(async () => {
    const [s, c, a] = await Promise.all([
      fetchDashboardSummary(),
      fetchDashboardCharts(days),
      fetchDashboardActivity(20),
    ]);
    return { s, c, a };
  }, [days]);

  const refresh = useCallback(async () => {
    setStatus("loading");
    setError(null);
    try {
      const { s, c, a } = await fetchAll();
      setSummary(s);
      setCharts(c);
      setActivity(a);
      setStatus(isEmptyTotals(s.totals) ? "empty" : "ready");
    } catch (e) {
      if (isNotConnectedError(e)) {
        setStatus("not-connected");
      } else if (e instanceof BackendError && e.status === 401) {
        setError("Session expired — please sign in again.");
        setStatus("error");
      } else {
        setError(e instanceof Error ? e.message : "Failed to load dashboard.");
        setStatus("error");
      }
    }
  }, [days, fetchAll]);

  useEffect(() => {
    if (userLoading) {
      setStatus("loading");
      return;
    }
    if (!firebaseUser) {
      setStatus("signed-out");
      return;
    }
    if (profile && !profile.web_connected) {
      setStatus("not-connected");
      return;
    }
    if (profile?.web_connected) {
      void refresh();
    }
  }, [userLoading, firebaseUser, profile, refresh]);

  // Silent 10s poll while data is on screen and the tab is visible — shows
  // freshly synced usage without a loading flash. Errors never disturb the
  // current view (the manual Refresh button surfaces them).
  useEffect(() => {
    if (typeof document === "undefined") return;
    const id = window.setInterval(() => {
      if (document.visibilityState !== "visible") return;
      if (statusRef.current !== "ready" && statusRef.current !== "empty") return;
      fetchAll()
        .then(({ s, c, a }) => {
          setSummary(s);
          setCharts(c);
          setActivity(a);
          setStatus(isEmptyTotals(s.totals) ? "empty" : "ready");
        })
        .catch(() => {});
    }, 10_000);
    return () => window.clearInterval(id);
  }, [fetchAll]);

  return { status, summary, charts, activity, error, refresh };
}
