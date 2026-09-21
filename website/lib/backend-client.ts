/**
 * Phase 1 — typed client for the private backend identity endpoints.
 *
 * Base URL: NEXT_PUBLIC_BACKEND_URL (e.g. https://api.androllm.app).
 * Auth: `Authorization: Bearer <Firebase ID token>` (see firebase-client.ts).
 * Identity always comes from the verified token server-side — this client
 * never sends a user id.
 *
 * Endpoints: POST /auth/verify, GET /auth/session, GET /me.
 * Phase 1 only — no analytics/sync calls live here.
 */
import { getIdToken } from "./firebase-client";

export type UserProfile = {
  id: string;
  firebase_uid: string;
  email: string | null;
  display_name: string | null;
  photo_url: string | null;
  created_at: string;
  last_seen_at: string;
  sync_enabled: boolean;
  settings: Record<string, unknown>;
  web_connected: boolean;
  web_connected_at: string | null;
};

export type AuthSession = {
  authenticated: boolean;
  user: UserProfile;
};

export class BackendError extends Error {
  status: number;
  constructor(status: number, message: string) {
    super(message);
    this.status = status;
  }
}

function baseUrl(): string {
  return (process.env.NEXT_PUBLIC_BACKEND_URL ?? "").trim().replace(/\/+$/, "");
}

export function isBackendConfigured(): boolean {
  return baseUrl().length > 0;
}

async function authedFetch(path: string, init?: RequestInit): Promise<Response> {
  const base = baseUrl();
  if (!base) throw new BackendError(0, "Backend is not configured (missing NEXT_PUBLIC_BACKEND_URL).");
  const token = await getIdToken();
  if (!token) throw new BackendError(401, "Not signed in (no Firebase ID token).");
  return fetch(`${base}${path}`, {
    ...init,
    headers: {
      "Content-Type": "application/json",
      ...(init?.headers ?? {}),
      Authorization: `Bearer ${token}`,
    },
  });
}

async function parseOrThrow<T>(res: Response): Promise<T> {
  if (res.status === 401) throw new BackendError(401, "Unauthorized (invalid or expired session).");
  if (!res.ok) throw new BackendError(res.status, `Backend request failed: HTTP ${res.status}`);
  return (await res.json()) as T;
}

/** Authenticated GET helper for Phase 4+ feature clients (token attached, errors typed). */
export async function apiGet<T>(path: string): Promise<T> {
  const res = await authedFetch(path, { method: "GET" });
  return parseOrThrow<T>(res);
}

/** Verify session with backend; creates/updates the profile server-side. */
export async function verifySession(opts?: {
  displayName?: string;
  photoUrl?: string;
}): Promise<UserProfile> {
  const res = await authedFetch("/auth/verify", {
    method: "POST",
    body: JSON.stringify({
      display_name: opts?.displayName ?? undefined,
      photo_url: opts?.photoUrl ?? undefined,
      device: { platform: "web", app_version: undefined },
    }),
  });
  return parseOrThrow<UserProfile>(res);
}

/** Lightweight session check for website startup. */
export async function fetchSession(): Promise<AuthSession> {
  const res = await authedFetch("/auth/session", { method: "GET" });
  return parseOrThrow<AuthSession>(res);
}

/** Current backend profile for the verified UID (Phase 4 dashboard gates on this). */
export async function fetchMe(): Promise<UserProfile> {
  const res = await authedFetch("/me", { method: "GET" });
  return parseOrThrow<UserProfile>(res);
}
