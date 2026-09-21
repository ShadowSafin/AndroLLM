/**
 * Phase 4 — personal analytics dashboard (metadata + client shell).
 * Data shown is ONLY the signed-in Firebase user's own usage.
 */
import type { Metadata } from "next";
import { DashboardScreen } from "./screen";

export const metadata: Metadata = {
  title: "Dashboard",
  description: "Your personal AndroLLM usage analytics — chats, tokens, models, and activity.",
};

export default function DashboardPage() {
  return <DashboardScreen />;
}
