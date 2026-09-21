"use client";

/**
 * Phase 2 — account linking status (no charts, no analytics yet).
 *
 * Same Firebase project as the Android app. After sign-in, the backend
 * profile (`GET /auth/session`) tells whether the Android app was explicitly
 * connected via "Connect Web Dashboard":
 * - `web_connected == true` → linked, analytics unlock in Phase 4.
 * - otherwise → "Connect your Android app to enable analytics."
 */
import Link from "next/link";
import { useState } from "react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import {
  isBackendConfigured,
} from "@/lib/backend-client";
import {
  isFirebaseConfigured,
  signOut,
} from "@/lib/firebase-client";
import { useBackendUser } from "@/hooks/use-backend-user";

export default function AccountPage() {
  const { firebaseUser, profile, loading, error, refresh } = useBackendUser();
  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);

  async function handleAuthAction(action: () => Promise<unknown>) {
    setBusy(true);
    setActionError(null);
    try {
      await action();
      await refresh();
    } catch (e) {
      setActionError(e instanceof Error ? e.message : "Something went wrong.");
    } finally {
      setBusy(false);
    }
  }

  if (!isFirebaseConfigured()) {
    return (
      <main className="container flex min-h-screen items-center justify-center py-24">
        <Card className="w-full max-w-lg">
          <CardHeader>
            <CardTitle>Account</CardTitle>
            <CardDescription>
              Firebase web config is missing. Add the public
              NEXT_PUBLIC_FIREBASE_* values to .env.local (see .env.example).
            </CardDescription>
          </CardHeader>
        </Card>
      </main>
    );
  }

  const linked = profile?.web_connected === true;

  return (
    <main className="container flex min-h-screen items-center justify-center py-24">
      <Card className="w-full max-w-lg">
        <CardHeader>
          <div className="flex items-center justify-between gap-4">
            <CardTitle>Account</CardTitle>
            {firebaseUser ? (
              <Badge variant={linked ? "ember" : "secondary"}>
                {loading ? "Checking…" : linked ? "Linked" : "Not linked"}
              </Badge>
            ) : (
              <Badge variant="secondary">Signed out</Badge>
            )}
          </div>
          <CardDescription>
            Sign in with the same account you use in the AndroLLM Android app.
          </CardDescription>
        </CardHeader>
        <CardContent className="flex flex-col gap-4">
          {!firebaseUser ? (
            <>
              <p className="text-sm text-gray-600 dark:text-gray-400">
                Use the same Firebase account on both the app and this site —
                your UID is the shared identity key.
              </p>
              <div className="flex flex-col gap-2 sm:flex-row">
                <Button asChild>
                  <Link href="/login">Sign in with Google or GitHub</Link>
                </Button>
              </div>
            </>
          ) : !isBackendConfigured() ? (
            <>
              <p className="text-sm text-gray-600 dark:text-gray-400">
                Signed in as {firebaseUser.email ?? "your account"}. The private
                backend is not configured (NEXT_PUBLIC_BACKEND_URL), so link
                status is unavailable.
              </p>
              <Button
                variant="secondary"
                disabled={busy}
                onClick={() => void handleAuthAction(signOut)}
              >
                Sign out
              </Button>
            </>
          ) : loading ? (
            <p className="text-sm text-gray-600 dark:text-gray-400">
              Checking link status…
            </p>
          ) : linked ? (
            <>
              <p className="text-sm text-gray-600 dark:text-gray-400">
                Your Android app is connected
                {profile?.web_connected_at
                  ? ` (since ${profile.web_connected_at.slice(0, 10)})`
                  : ""}
                . Your analytics will appear here once sync is available.
              </p>
              <div className="flex flex-col gap-2 sm:flex-row">
                <Button asChild>
                  <Link href="/dashboard">View dashboard</Link>
                </Button>
                <Button
                  variant="secondary"
                  disabled={busy}
                  onClick={() => void handleAuthAction(signOut)}
                >
                  Sign out
                </Button>
              </div>
            </>
          ) : (
            <>
              <p className="text-sm text-gray-600 dark:text-gray-400">
                Connect your Android app to enable analytics. In the app, go to
                Settings → Account &amp; Sync →{" "}
                <strong>Connect Web Dashboard</strong> and confirm.
              </p>
              <div className="flex flex-col gap-2 sm:flex-row">
                <Button disabled={busy} onClick={() => void refresh()}>
                  Recheck status
                </Button>
                <Button
                  variant="secondary"
                  disabled={busy}
                  onClick={() => void handleAuthAction(signOut)}
                >
                  Sign out
                </Button>
              </div>
            </>
          )}
          {(error ?? actionError) && (
            <p className="text-sm text-red-500">{error ?? actionError}</p>
          )}
        </CardContent>
      </Card>
    </main>
  );
}
