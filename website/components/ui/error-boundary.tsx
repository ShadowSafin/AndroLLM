"use client";

import { Component } from "react";
import type { ReactNode } from "react";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";

/**
 * Catches render crashes below it and shows a retry card instead of a blank
 * page. Retry remounts the children (attempt key) for a clean slate.
 * Used on auth entry points where a white screen strands the user.
 */
export class ErrorBoundary extends Component<{ children: ReactNode }, { crashed: boolean; attempt: number }> {
  state = { crashed: false, attempt: 0 };

  static getDerivedStateFromError(): { crashed: boolean; attempt: number } {
    return { crashed: true, attempt: 0 };
  }

  componentDidCatch(): void {
    // Intentionally silent: crash details stay in the console for debugging.
  }

  private retry = () => this.setState((s) => ({ crashed: false, attempt: s.attempt + 1 }));

  render() {
    if (!this.state.crashed) {
      return <div key={this.state.attempt}>{this.props.children}</div>;
    }
    return (
      <main className="container flex min-h-screen items-center justify-center py-24">
        <Card className="w-full max-w-lg" data-testid="error-boundary-fallback">
          <CardHeader>
            <CardTitle>Something didn&apos;t load</CardTitle>
            <CardDescription>The sign-in screen hit a glitch. Your account is safe.</CardDescription>
          </CardHeader>
          <CardContent>
            <Button onClick={this.retry}>Try again</Button>
          </CardContent>
        </Card>
      </main>
    );
  }
}
