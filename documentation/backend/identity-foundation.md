# AndroLLM Identity Foundation (Phase 1)

Shared identity layer for the Android app and the web dashboard.

- Firebase Authentication is the **only** login system (app + web share the same Firebase project).
- The private backend verifies Firebase ID tokens with the **Firebase Admin SDK** (server-side only).
- The backend owns a stable user profile keyed by `firebase_uid`.
- The Firebase UID is the canonical identity key. **Never trust client-supplied user IDs.**

Phase 1 scope: identity only. No analytics, no sync, no charts.

---

## 1. Flow

```
Android app                          Private backend                        Website
───────────                          ───────────────                        ───────
Firebase sign-in (Google/GitHub)
  └─> FirebaseUser (uid, email,
      displayName, photoUrl)
        │
        ├── getIdToken() ──> Authorization: Bearer <ID_TOKEN>
        │                      │
        │                      ├── POST /auth/verify ──> verifyIdToken()
        │                      │     upsert users by firebase_uid
        │                      │     update last_seen_at
        │                      │     return UserProfile
        │                      │
        │                      ├── GET /auth/session ──> verify + return profile
        │                      └── GET /me ──> verify + return profile
        │
        └── (Phase 2+) explicit "Connect Web Dashboard"
              consent stored server-side before ANY usage sync

Website (same Firebase project):
  Firebase web sign-in (Google/GitHub, same project)
    └─> getIdToken() ──> same 3 endpoints ──> same UserProfile
```

Both clients attach the token the same way:

```
Authorization: Bearer <Firebase ID token>
```

---

## 2. Endpoints (private backend)

Base URL is environment-configured, never hard-coded:

- Android: `BuildConfig.BACKEND_BASE_URL` (or DataStore remote config)
- Web: `NEXT_PUBLIC_BACKEND_URL`

### POST /auth/verify

Verify the caller's ID token and create/update the user profile.

Request:

```http
POST /auth/verify HTTP/1.1
Authorization: Bearer <ID_TOKEN>
Content-Type: application/json

{
  "display_name": "Optional client hint (ignored for identity)",
  "photo_url": "Optional client hint (ignored for identity)",
  "device": { "platform": "android|web", "app_version": "1.1.6" }
}
```

Server behavior:

1. Extract Bearer token. 401 if missing/malformed.
2. `admin.auth().verifyIdToken(idToken)` — 401 if invalid/expired/revoked.
3. Take `uid`, `email`, `name`, `picture` **from the verified token**, not the body.
4. `INSERT ... ON CONFLICT (firebase_uid) DO UPDATE`:
   - set `email`, `display_name`, `photo_url` from token (fallback to body hints only if token lacks them)
   - set `last_seen_at = now()`
   - never overwrite `id`, `firebase_uid`, `created_at`, `web_connected`, `web_connected_at`
5. Return the profile.

Response `200`:

```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "firebase_uid": "firebaseUid123",
  "email": "user@example.com",
  "display_name": "Ada",
  "photo_url": "https://...",
  "created_at": "2026-09-21T00:00:00.000Z",
  "last_seen_at": "2026-09-21T00:00:00.000Z",
  "sync_enabled": false,
  "settings": {},
  "web_connected": false,
  "web_connected_at": null
}
```

Errors: `401 { "error": "unauthorized" }`, `503 { "error": "auth_unavailable" }`.

### GET /auth/session

Lightweight session check. Same auth header, no body.
Returns `{ "authenticated": true, "user": <UserProfile> }` or `401`.

Used by both clients on startup to decide: guest vs signed-in vs backend-linked.

### GET /me

Returns the current `UserProfile` for the verified UID. `401` if token invalid.
The website dashboard (Phase 4) calls this after Firebase web sign-in and gates
analytics on `web_connected == true`.

### POST /auth/connect (Phase 2 — explicit consent)

Marks the verified UID as connected to the web dashboard. Called by the
Android app ONLY after the user taps "Connect Web Dashboard" and confirms.

```http
POST /auth/connect HTTP/1.1
Authorization: Bearer <ID_TOKEN>
Content-Type: application/json

{}
```

Server behavior (private backend):

1. Verify the Bearer token via Admin SDK — 401 if invalid/expired/revoked.
2. `UPDATE users SET web_connected = true, web_connected_at = COALESCE(web_connected_at, now()), last_seen_at = now() WHERE firebase_uid = $uid` (idempotent).
3. Return the updated `UserProfile`.
4. Uploads no usage data — this endpoint only flips the consent flag.

### DELETE /auth/connect (Phase 2 — revoke consent)

Clears `web_connected` for the verified UID (idempotent) and returns the
updated `UserProfile`. Same auth header, no body. The Android settings card
offers this as "Disconnect"; Phase 3+ sync must stop for the UID afterwards.

### POST /events/batch (Phase 3 — usage ingestion, gated)

Accepts `{ device, events[] }` (1–500 items) with per-item partial success:
`{ accepted, duplicates, rejected[], device_id }`. Each event carries an
optional `source_page` naming the in-app surface that produced it
(`cloud_usage_dashboard` | `developer_page`). Retries reuse the same
`event_id` and dedupe server-side. 403 `web_not_connected` for unlinked users.

### POST /analytics/snapshot (Phase 3 revised — page rollups, gated)

Accepts one page-level snapshot per call:
`{ device, source_page, snapshot_type, snapshot_id, payload }`, where
`source_page` is `cloud_usage_dashboard` or `developer_page` and
`snapshot_id` is a stable hourly bucket so retries return
`{ stored: false, duplicate: true }` instead of double-storing. Payloads are
capped at 100KB. 403 `web_not_connected` for unlinked users.

Android collection (no UI changes — existing screens only):
- Cloud usage dashboard → `CloudUsageMeter` records (stable UUID ids) become
  `cloud`-engine events; the dashboard rollup becomes a `dashboard_rollup` snapshot.
- Developer page → `TelemetryRepository` generations become `local`-engine
  `chat_completed` events (output ⇒ success); the telemetry summary becomes a
  `telemetry_summary` snapshot.
- Uploads run in `AnalyticsSyncWorker` (WorkManager, 6-hourly, network-required)
  and only after `GET /me` confirms `web_connected`; watermarks in DataStore
  skip already-sent windows.
- Freshness: every recorded cloud request and completed local generation fires
  a listener that `AnalyticsSyncTrigger` debounces (~8s) into a one-shot upload,
  so usage lands in Supabase within ~10s; the website silently re-fetches every
  10s while visible. Bursts coalesce; offline/guest runs no-op.

---

## 3. UserProfile fields (Phase 1)

| Field | Type | Notes |
|---|---|---|
| `id` | uuid PK | server-generated, never client-supplied |
| `firebase_uid` | text UNIQUE NOT NULL | canonical key, from verified token |
| `email` | text nullable | from verified token |
| `display_name` | text nullable | from verified token |
| `photo_url` | text nullable | from verified token |
| `created_at` | timestamptz NOT NULL | set once |
| `last_seen_at` | timestamptz NOT NULL | updated on every `/auth/verify` |
| `sync_enabled` | bool NOT NULL DEFAULT false | Phase 1 default false; Phase 2/3 flip only after explicit connect |
| `settings` | jsonb NOT NULL DEFAULT '{}' | per-user prefs, opaque to auth |
| `web_connected` | bool NOT NULL DEFAULT false | added/used in Phase 2 (present in template schema for forward compat) |
| `web_connected_at` | timestamptz nullable | set once on explicit connect (Phase 2) |

Full DDL: [`schema-identity.sql`](schema-identity.sql).

---

## 4. Security rules (must-follow)

1. Never trust client-supplied user IDs — only `verifiedToken.uid`.
2. Trust only verified Firebase ID tokens (Admin SDK). Check expiry/revocation.
3. Use Firebase UID as source of truth for every row (`WHERE firebase_uid = $uid`).
4. No usage/analytics sync before explicit app-to-web connection (enforced Phase 2+).
5. Website shows only the current user's data — no admin/global reads on these endpoints.
6. Backend secrets stay private: service-account JSON and `.env` are never committed.
   The public repo contains only `.env.example` files and a backend **template**.

---

## 5. What lives where (repo split)

| Location | Contents |
|---|---|
| Public repo (`androllm`) | This contract, `schema-identity.sql`, Android `core/network/identity/` client, website `lib/firebase-client.ts` + `lib/backend-client.ts` + `hooks/use-backend-user.ts`, `website/.env.example`, backend **template** under `tools/backend-template/` (no secrets) |
| Private backend repo | Copy of `tools/backend-template/` + real `.env` + service-account JSON (gitignored). The only place Admin SDK keys exist. |

See `tools/backend-template/README.md` for private-repo setup.

---

## 6. Phase boundaries (do not cross)

- Phase 1: this document + 3 endpoints + profile. No events, no sync, no charts.
- Phase 2: adds the Android "Connect Web Dashboard" consent + `web_connected` gating.
- Phase 3: adds event ingestion (only for `web_connected = true` users).
- Phase 4: adds the website analytics dashboard (only for connected accounts).
