import { render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ErrorBoundary } from "./error-boundary";

vi.mock("next/link", async () => {
  const React = await import("react");
  return {
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    default: ({ children, href }: any) =>
      React.createElement("a", { href: typeof href === "string" ? href : "#" }, children),
  };
});

function Boom({ onRender }: { onRender?: () => void }): never {
  onRender?.();
  throw new Error("boom");
}

describe("ErrorBoundary", () => {
  // React replays a caught concurrent-render error asynchronously; keep
  // console.error muted past test end so the intentional crash never leaks
  // into the suite as an unhandled error.
  beforeEach(() => {
    vi.spyOn(console, "error").mockImplementation(() => {});
  });
  afterEach(async () => {
    await new Promise((r) => setTimeout(r, 150));
    vi.restoreAllMocks();
  });

  it("shows the retry fallback instead of a blank page on crash", () => {
    render(
      <ErrorBoundary>
        <Boom />
      </ErrorBoundary>,
    );
    expect(screen.getByTestId("error-boundary-fallback")).toBeDefined();
    expect(screen.getByRole("button", { name: /try again/i })).toBeDefined();
  });
});
