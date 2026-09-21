/**
 * Phase 1 — Firebase web client (shared Firebase project with the Android app).
 *
 * Uses only NEXT_PUBLIC_* env vars. Firebase web config (apiKey, projectId, …)
 * is public by design — it is not a secret. The ID token is the credential;
 * it is sent as `Authorization: Bearer <token>` and verified server-side
 * with the Admin SDK. Never send a user id; the backend derives identity
 * from the verified token (`decoded.uid`).
 */
import { getApp, getApps, initializeApp, type FirebaseApp } from "firebase/app";
import {
  getAuth,
  GoogleAuthProvider,
  GithubAuthProvider,
  signInWithPopup,
  signOut as firebaseSignOut,
  onAuthStateChanged,
  type Auth,
  type User,
} from "firebase/auth";

const firebaseConfig = {
  apiKey: process.env.NEXT_PUBLIC_FIREBASE_API_KEY,
  authDomain: process.env.NEXT_PUBLIC_FIREBASE_AUTH_DOMAIN,
  projectId: process.env.NEXT_PUBLIC_FIREBASE_PROJECT_ID,
  storageBucket: process.env.NEXT_PUBLIC_FIREBASE_STORAGE_BUCKET,
  messagingSenderId: process.env.NEXT_PUBLIC_FIREBASE_MESSAGING_SENDER_ID,
  appId: process.env.NEXT_PUBLIC_FIREBASE_APP_ID,
};

let app: FirebaseApp | null = null;
let auth: Auth | null = null;

export function isFirebaseConfigured(): boolean {
  return Boolean(
    firebaseConfig.apiKey && firebaseConfig.authDomain && firebaseConfig.projectId && firebaseConfig.appId,
  );
}

function getFirebaseApp(): FirebaseApp | null {
  if (!isFirebaseConfigured()) return null;
  if (app) return app;
  app = getApps().length > 0 ? getApp() : initializeApp(firebaseConfig);
  return app;
}

export function getFirebaseAuth(): Auth | null {
  if (auth) return auth;
  const firebaseApp = getFirebaseApp();
  if (!firebaseApp) return null;
  auth = getAuth(firebaseApp);
  return auth;
}

/** Google sign-in (same Firebase project as the Android app). */
export async function signInWithGoogle(): Promise<User> {
  const firebaseAuth = getFirebaseAuth();
  if (!firebaseAuth) throw new Error("Firebase is not configured (missing NEXT_PUBLIC_FIREBASE_* env).");
  const result = await signInWithPopup(firebaseAuth, new GoogleAuthProvider());
  return result.user;
}

/** GitHub sign-in (same Firebase project as the Android app). */
export async function signInWithGitHub(): Promise<User> {
  const firebaseAuth = getFirebaseAuth();
  if (!firebaseAuth) throw new Error("Firebase is not configured (missing NEXT_PUBLIC_FIREBASE_* env).");
  const provider = new GithubAuthProvider();
  provider.addScope("read:user");
  provider.addScope("user:email");
  const result = await signInWithPopup(firebaseAuth, provider);
  return result.user;
}

export async function signOut(): Promise<void> {
  const firebaseAuth = getFirebaseAuth();
  if (firebaseAuth) await firebaseSignOut(firebaseAuth);
}

/** Current user's Firebase ID token, or null when signed out / unconfigured. */
export async function getIdToken(forceRefresh = false): Promise<string | null> {
  const firebaseAuth = getFirebaseAuth();
  const user = firebaseAuth?.currentUser;
  if (!user) return null;
  return user.getIdToken(forceRefresh);
}

export function subscribeToAuthChanges(callback: (user: User | null) => void): () => void {
  const firebaseAuth = getFirebaseAuth();
  if (!firebaseAuth) {
    callback(null);
    return () => {};
  }
  return onAuthStateChanged(firebaseAuth, callback);
}

export type { User };
