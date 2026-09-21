import type { Metadata } from "next";
import { LoginScreen } from "./screen";

export const metadata: Metadata = {
  title: "Sign in",
  description: "Sign in to AndroLLM with Google or GitHub — the same account as your Android app.",
};

export default function LoginPage() {
  return <LoginScreen />;
}
