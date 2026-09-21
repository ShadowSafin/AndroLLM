"use client";

/**
 * Phase 1 — React hook bridging Firebase web auth and the backend profile.
 *
 * Flow: Firebase sign-in (same project as Android) → Firebase ID token →
 * backend `/auth/session` → shared UserProfile. Returns the Firebase user
 * plus the backend profile so Phase 4 can gate analytics on
 * `profile.web_connected`.
 */
import { useCallback, useEffect, useState } from "react";
import {
  subscribeToAuthChanges,
  type User as FirebaseUser,
} from "@/lib/firebase-client";
import {
  BackendError,
  fetchSession,
  type UserProfile,
} from "@/lib/backend-client";

export type BackendUserState = {
  firebaseUser: FirebaseUser | null;
  profile: UserProfile | null;
  loading: boolean;
  error: string | null;
  refresh: () => Promise<void>;
};

export function useBackendUser(): BackendUserState {
  const [firebaseUser, setFirebaseUser] = useState<FirebaseUser | null>(null);
  const [profile, setProfile] = useState<UserProfile | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const session = await fetchSession();
      setProfile(session.user);
    } catch (e) {
      setProfile(null);
      if (e instanceof BackendError && e.status === 401) {
        // Signed out or backend has no row yet — not fatal for Phase 1.
        setError(null);
      } else {
        setError(e instanceof Error ? e.message : "Failed to load backend profile.");
      }
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    const unsubscribe = subscribeToAuthChanges((user) => {
      setFirebaseUser(user);
      if (!user) {
        setProfile(null);
        setError(null);
        setLoading(false);
        return;
      }
      void refresh();
    });
    return unsubscribe;
  }, [refresh]);

  return { firebaseUser, profile, loading, error, refresh };
}
