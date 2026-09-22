"use client";

import { useEffect } from "react";
import { useRouter } from "next/navigation";
import { AuthComponent } from "@/components/ui/sign-up";
import { ErrorBoundary } from "@/components/ui/error-boundary";
import { Logo } from "@/components/logo";
import { consumeRedirectResult, subscribeToAuthChanges } from "@/lib/firebase-client";

export function LoginScreen() {
  const router = useRouter();

  useEffect(() => {
    // Complete a redirect sign-in (popup-blocked fallback); the subscriber
    // below picks the user up either way.
    void consumeRedirectResult().catch(() => {});
    const unsubscribe = subscribeToAuthChanges((user) => {
      if (user) router.replace("/dashboard");
    });
    return unsubscribe;
  }, [router]);

  return (
    <ErrorBoundary>
      <AuthComponent
        logo={<Logo compact />}
        brandName="AndroLLM"
        redirectTo="/dashboard"
        onAuthenticated={() => router.push("/dashboard")}
      />
    </ErrorBoundary>
  );
}
