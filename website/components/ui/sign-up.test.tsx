import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { AuthComponent } from "./sign-up";
import { signInWithGitHub, signInWithGoogle, signInWithRedirectFallback } from "@/lib/firebase-client";

vi.mock("@/lib/firebase-client", () => ({
  signInWithGoogle: vi.fn(),
  signInWithGitHub: vi.fn(),
  signInWithRedirectFallback: vi.fn(),
  consumeRedirectResult: vi.fn(),
}));

afterEach(() => cleanup());

const googleMock = vi.mocked(signInWithGoogle);
const githubMock = vi.mocked(signInWithGitHub);
const redirectMock = vi.mocked(signInWithRedirectFallback);

describe("AuthComponent (OAuth only)", () => {
  it("offers Google and GitHub with no manual email option", () => {
    render(<AuthComponent onAuthenticated={() => {}} />);
    expect(screen.getByRole("button", { name: /google/i })).toBeDefined();
    expect(screen.getByRole("button", { name: /github/i })).toBeDefined();
    expect(screen.queryByPlaceholderText(/email/i)).toBeNull();
    expect(screen.queryByPlaceholderText(/password/i)).toBeNull();
    expect(screen.queryByRole("textbox")).toBeNull();
  });

  it("celebrates and redirects after Google sign-in", async () => {
    googleMock.mockResolvedValueOnce({ email: "a@example.com" } as never);
    const onAuthenticated = vi.fn();
    render(<AuthComponent onAuthenticated={onAuthenticated} />);

    fireEvent.click(screen.getByRole("button", { name: /google/i }));

    await waitFor(() => expect(screen.getByText(/taking you to your dashboard/i)).toBeDefined(), {
      timeout: 3000,
    });
    await waitFor(() => expect(onAuthenticated).toHaveBeenCalledTimes(1), { timeout: 3000 });
  });

  it("falls back to redirect when the popup is blocked", async () => {
    googleMock.mockRejectedValueOnce(Object.assign(new Error("blocked"), { code: "auth/popup-blocked" }));
    redirectMock.mockImplementationOnce(() => new Promise(() => {}));
    const onAuthenticated = vi.fn();
    render(<AuthComponent onAuthenticated={onAuthenticated} />);

    fireEvent.click(screen.getByRole("button", { name: /google/i }));

    await waitFor(() => expect(screen.getByText(/redirecting to provider/i)).toBeDefined(), {
      timeout: 3000,
    });
    expect(redirectMock).toHaveBeenCalledWith("google");
    expect(onAuthenticated).not.toHaveBeenCalled();
  });

  it("shows a friendly error when GitHub sign-in fails", async () => {
    githubMock.mockRejectedValueOnce(Object.assign(new Error("popup closed"), { code: "auth/popup-closed-by-user" }));
    const onAuthenticated = vi.fn();
    render(<AuthComponent onAuthenticated={onAuthenticated} />);

    fireEvent.click(screen.getByRole("button", { name: /github/i }));

    await waitFor(() => expect(screen.getByText(/window was closed/i)).toBeDefined(), { timeout: 3000 });
    expect(onAuthenticated).not.toHaveBeenCalled();
  });
});
