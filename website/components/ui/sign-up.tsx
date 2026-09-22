"use client";

/**
 * OAuth-only sign-in/sign-up card (Google + GitHub via Firebase).
 *
 * Adapted to this repo: no email/password flow (product decision — the
 * Android app offers exactly Google + GitHub), themed to the site's dark
 * palette instead of stock shadcn tokens, and wired to
 * `@/lib/firebase-client`. Visual system kept from the original: glass
 * buttons, blur-fade entrances, rotating hero words, gradient backdrop,
 * and a confetti burst on success.
 */
import { cn } from "@/lib/utils";
import {
  Children,
  createContext,
  forwardRef,
  useCallback,
  useEffect,
  useImperativeHandle,
  useMemo,
  useRef,
  useState,
} from "react";
import type { ComponentPropsWithRef, ReactNode } from "react";
import { cva, type VariantProps } from "class-variance-authority";
import { AlertCircle, Gem, Loader, PartyPopper } from "lucide-react";
import {
  AnimatePresence,
  motion,
  useInView,
  type Transition,
  type Variants,
} from "framer-motion";
import type {
  GlobalOptions as ConfettiGlobalOptions,
  CreateTypes as ConfettiInstance,
  Options as ConfettiOptions,
} from "canvas-confetti";
import confetti from "canvas-confetti";
import { signInWithGitHub, signInWithGoogle, signInWithRedirectFallback } from "@/lib/firebase-client";

// --- CONFETTI ---
type ConfettiApi = { fire: (options?: ConfettiOptions) => void };
export type ConfettiRef = ConfettiApi | null;
const ConfettiContext = createContext<ConfettiApi>({} as ConfettiApi);
void ConfettiContext;

const Confetti = forwardRef<
  ConfettiRef,
  ComponentPropsWithRef<"canvas"> & {
    options?: ConfettiOptions;
    globalOptions?: ConfettiGlobalOptions;
    manualstart?: boolean;
  }
>((props, ref) => {
  const { options, globalOptions = { resize: true, useWorker: true }, manualstart = false, ...rest } = props;
  const instanceRef = useRef<ConfettiInstance | null>(null);
  const canvasRef = useCallback(
    (node: HTMLCanvasElement | null) => {
      if (node !== null) {
        if (instanceRef.current) return;
        try {
          instanceRef.current = confetti.create(node, { ...globalOptions, resize: true });
        } catch {
          instanceRef.current = null;
        }
      } else if (instanceRef.current) {
        try {
          instanceRef.current.reset();
        } catch {
          /* noop */
        }
        instanceRef.current = null;
      }
    },
    [globalOptions],
  );
  const fire = useCallback(
    (opts: ConfettiOptions = {}) => {
      try {
        instanceRef.current?.({ ...options, ...opts });
      } catch {
        /* canvas-confetti needs a real 2d context; never break auth for it */
      }
    },
    [options],
  );
  const api = useMemo(() => ({ fire }), [fire]);
  useImperativeHandle(ref, () => api, [api]);
  useEffect(() => {
    if (!manualstart) fire();
  }, [manualstart, fire]);
  return <canvas ref={canvasRef} {...rest} />;
});
Confetti.displayName = "Confetti";

// --- TEXT LOOP ---
type TextLoopProps = {
  children: ReactNode[];
  className?: string;
  interval?: number;
  transition?: Transition;
  variants?: Variants;
  onIndexChange?: (index: number) => void;
};
export function TextLoop({
  children,
  className,
  interval = 2,
  transition = { duration: 0.3 },
  variants,
  onIndexChange,
}: TextLoopProps) {
  const [currentIndex, setCurrentIndex] = useState(0);
  const items = Children.toArray(children);
  useEffect(() => {
    if (items.length < 2) return;
    const timer = setInterval(() => {
      setCurrentIndex((current) => {
        const next = (current + 1) % items.length;
        onIndexChange?.(next);
        return next;
      });
    }, interval * 1000);
    return () => clearInterval(timer);
  }, [items.length, interval, onIndexChange]);
  const motionVariants: Variants = {
    initial: { y: 20, opacity: 0 },
    animate: { y: 0, opacity: 1 },
    exit: { y: -20, opacity: 0 },
  };
  return (
    <div className={cn("relative inline-block whitespace-nowrap", className)}>
      <AnimatePresence mode="wait" initial={false}>
        <motion.div
          key={currentIndex}
          initial="initial"
          animate="animate"
          exit="exit"
          transition={transition}
          variants={variants || motionVariants}
        >
          {items[currentIndex]}
        </motion.div>
      </AnimatePresence>
    </div>
  );
}

// --- BLUR FADE ---
type BlurFadeProps = {
  children: ReactNode;
  className?: string;
  duration?: number;
  delay?: number;
  yOffset?: number;
  inView?: boolean;
  blur?: string;
};
function BlurFade({
  children,
  className,
  duration = 0.4,
  delay = 0,
  yOffset = 6,
  inView = true,
  blur = "6px",
}: BlurFadeProps) {
  const ref = useRef<HTMLDivElement>(null);
  const inViewResult = useInView(ref, { once: true });
  const isInView = !inView || inViewResult;
  const defaultVariants: Variants = {
    hidden: { y: yOffset, opacity: 0, filter: `blur(${blur})` },
    visible: { y: -yOffset, opacity: 1, filter: "blur(0px)" },
  };
  return (
    <motion.div
      ref={ref}
      initial="hidden"
      animate={isInView ? "visible" : "hidden"}
      exit="hidden"
      variants={defaultVariants}
      transition={{ delay: 0.04 + delay, duration, ease: "easeOut" }}
      className={className}
    >
      {children}
    </motion.div>
  );
}

// --- GLASS BUTTON ---
const glassButtonVariants = cva("relative isolate cursor-pointer rounded-full transition-all", {
  variants: {
    size: {
      default: "text-base font-medium",
      sm: "text-sm font-medium",
      lg: "text-lg font-medium",
      icon: "h-10 w-10",
    },
  },
  defaultVariants: { size: "default" },
});
const glassButtonTextVariants = cva("glass-button-text relative block select-none tracking-tighter", {
  variants: {
    size: {
      default: "px-6 py-3.5",
      sm: "px-4 py-2",
      lg: "px-8 py-4",
      icon: "flex h-10 w-10 items-center justify-center",
    },
  },
  defaultVariants: { size: "default" },
});
export interface GlassButtonProps
  extends React.ButtonHTMLAttributes<HTMLButtonElement>,
    VariantProps<typeof glassButtonVariants> {
  contentClassName?: string;
}
const GlassButton = forwardRef<HTMLButtonElement, GlassButtonProps>(
  ({ className, children, size, contentClassName, onClick, ...props }, ref) => {
    // Single native button: wrapper, control, and shadow all in one element
    // so a click can never double-fire (the old div>button forwarder fired
    // twice when the click landed on the icon/text spans).
    return (
      <button
        className={cn("glass-button-wrap", "glass-button", "relative z-10", glassButtonVariants({ size }), className)}
        ref={ref}
        onClick={onClick}
        {...props}
      >
        <span className={cn(glassButtonTextVariants({ size }), contentClassName)}>{children}</span>
        <span className="glass-button-shadow pointer-events-none rounded-full" aria-hidden />
      </button>
    );
  },
);
GlassButton.displayName = "GlassButton";

// --- GRADIENT BACKDROP (site palette) ---
const GradientBackground = () => (
  <>
    <style>
      {` @keyframes float1 { 0% { transform: translate(0, 0); } 50% { transform: translate(-10px, 10px); } 100% { transform: translate(0, 0); } } @keyframes float2 { 0% { transform: translate(0, 0); } 50% { transform: translate(10px, -10px); } 100% { transform: translate(0, 0); } } `}
    </style>
    <svg
      width="100%"
      height="100%"
      viewBox="0 0 800 600"
      fill="none"
      xmlns="http://www.w3.org/2000/svg"
      preserveAspectRatio="xMidYMid slice"
      className="absolute left-0 top-0 h-full w-full"
    >
      <defs>
        <linearGradient id="auth_grad1" x1="0%" y1="0%" x2="100%" y2="100%">
          <stop offset="0%" style={{ stopColor: "#7A5CFF", stopOpacity: 0.28 }} />
          <stop offset="100%" style={{ stopColor: "#9D85FF", stopOpacity: 0.18 }} />
        </linearGradient>
        <linearGradient id="auth_grad2" x1="0%" y1="0%" x2="100%" y2="100%">
          <stop offset="0%" style={{ stopColor: "#F25DFF", stopOpacity: 0.22 }} />
          <stop offset="50%" style={{ stopColor: "#CFC2FF", stopOpacity: 0.16 }} />
          <stop offset="100%" style={{ stopColor: "#34D399", stopOpacity: 0.14 }} />
        </linearGradient>
        <radialGradient id="auth_grad3" cx="50%" cy="50%" r="50%">
          <stop offset="0%" style={{ stopColor: "#FB7185", stopOpacity: 0.2 }} />
          <stop offset="100%" style={{ stopColor: "#60A5FA", stopOpacity: 0.1 }} />
        </radialGradient>
        <filter id="auth_blur1" x="-50%" y="-50%" width="200%" height="200%">
          <feGaussianBlur stdDeviation="45" />
        </filter>
        <filter id="auth_blur2" x="-50%" y="-50%" width="200%" height="200%">
          <feGaussianBlur stdDeviation="35" />
        </filter>
        <filter id="auth_blur3" x="-50%" y="-50%" width="200%" height="200%">
          <feGaussianBlur stdDeviation="55" />
        </filter>
      </defs>
      <g style={{ animation: "float1 20s ease-in-out infinite" }}>
        <ellipse cx="200" cy="520" rx="180" ry="120" fill="url(#auth_grad1)" filter="url(#auth_blur1)" transform="rotate(-30 200 520)" />
        <rect x="540" y="120" width="200" height="170" rx="60" fill="url(#auth_grad2)" filter="url(#auth_blur2)" transform="rotate(15 640 205)" />
      </g>
      <g style={{ animation: "float2 25s ease-in-out infinite" }}>
        <circle cx="650" cy="450" r="100" fill="url(#auth_grad3)" filter="url(#auth_blur3)" opacity="0.5" />
        <ellipse cx="60" cy="140" rx="120" ry="80" fill="#7A5CFF" filter="url(#auth_blur2)" opacity="0.25" />
      </g>
    </svg>
  </>
);

// --- PROVIDER ICONS ---
const GoogleIcon = (props: React.SVGProps<SVGSVGElement>) => (
  <svg {...props} xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64" className="h-6 w-6">
    <g fillRule="evenodd" fill="none">
      <g fillRule="nonzero" transform="translate(3, 2)">
        <path fill="#4285F4" d="M57.8123233,30.1515267 C57.8123233,27.7263183 57.6155321,25.9565533 57.1896408,24.1212666 L29.4960833,24.1212666 L29.4960833,35.0674653 L45.7515771,35.0674653 C45.4239683,37.7877475 43.6542033,41.8844383 39.7213169,44.6372555 L39.6661883,45.0037254 L48.4223791,51.7870338 L49.0290201,51.8475849 C54.6004021,46.7020943 57.8123233,39.1313952 57.8123233,30.1515267"></path>
        <path fill="#34A853" d="M29.4960833,58.9921667 C37.4599129,58.9921667 44.1456164,56.3701671 49.0290201,51.8475849 L39.7213169,44.6372555 C37.2305867,46.3742596 33.887622,47.5868638 29.4960833,47.5868638 C21.6960582,47.5868638 15.0758763,42.4415991 12.7159637,35.3297782 L12.3700541,35.3591501 L3.26524241,42.4054492 L3.14617358,42.736447 C7.9965904,52.3717589 17.959737,58.9921667 29.4960833,58.9921667"></path>
        <path fill="#FBBC05" d="M12.7159637,35.3297782 C12.0932812,33.4944915 11.7329116,31.5279353 11.7329116,29.4960833 C11.7329116,27.4640054 12.0932812,25.4976752 12.6832029,23.6623884 L12.6667095,23.2715173 L3.44779955,16.1120237 L3.14617358,16.2554937 C1.14708246,20.2539019 0,24.7439491 0,29.4960833 C0,34.2482175 1.14708246,38.7380388 3.14617358,42.736447 L12.7159637,35.3297782"></path>
        <path fill="#EB4335" d="M29.4960833,11.4050769 C35.0347044,11.4050769 38.7707997,13.7975244 40.9011602,15.7968415 L49.2255853,7.66898166 C44.1130815,2.91684746 37.4599129,0 29.4960833,0 C17.959737,0 7.9965904,6.62018183 3.14617358,16.2554937 L12.6832029,23.6623884 C15.0758763,16.5505675 21.6960582,11.4050769 29.4960833,11.4050769"></path>
      </g>
    </g>
  </svg>
);
const GitHubIcon = (props: React.SVGProps<SVGSVGElement>) => (
  <svg {...props} xmlns="http://www.w3.org/2000/svg" viewBox="0 0 16 16" className="h-6 w-6">
    <path fill="currentColor" d="M8 0C3.58 0 0 3.58 0 8c0 3.54 2.29 6.53 5.47 7.59.4.07.55-.17.55-.38 0-.19-.01-.82-.01-1.49-2.01.37-2.53-.49-2.69-.94-.09-.23-.48-.94-.82-1.13-.28-.15-.68-.52-.01-.53.63-.01 1.08.58 1.23.82.72 1.21 1.87.87 2.33.66.07-.52.28-.87.51-1.07-1.78-.2-3.64-.89-3.64-3.95 0-.87.31-1.59.82-2.15-.08-.2-.36-1.02.08-2.12 0 0 .67-.21 2.2.82.64-.18 1.32-.27 2-.27.68 0 1.36.09 2 .27 1.53-1.04 2.2-.82 2.2-.82.44 1.1.16 1.92.08 2.12.51.56.82 1.27.82 2.15 0 3.07-1.87 3.75-3.65 3.95.29.25.54.73.54 1.48 0 1.07-.01 1.93-.01 2.2 0 .21.15.46.55.38A8.013 8.013 0 0 0 16 8c0-4.42-3.58-8-8-8z" />
  </svg>
);

const DefaultLogo = () => (
  <div className="rounded-md bg-[var(--accent)] p-1.5 text-white">
    <Gem className="h-4 w-4" />
  </div>
);

// --- MAIN COMPONENT ---
type AuthStatus = "idle" | "working" | "success" | "error";

interface AuthComponentProps {
  logo?: ReactNode;
  brandName?: string;
  /** Where to send the user after a successful sign-in. Defaults to /dashboard. */
  redirectTo?: string;
  /** Overrides the default router push (used by tests). */
  onAuthenticated?: () => void;
}

function friendlyAuthError(e: unknown): string {
  const code = typeof e === "object" && e !== null ? (e as { code?: unknown }).code : undefined;
  if (code === "auth/popup-closed-by-user") return "Sign-in window was closed — try again.";
  if (code === "auth/cancelled-popup-request") return "Another sign-in is already open.";
  if (code === "auth/unauthorized-domain") return "This domain isn't authorized in Firebase console.";
  if (code === "auth/network-request-failed") return "Network error — check your connection and retry.";
  if (code === "auth/account-exists-with-different-credential")
    return "This email is already linked to another provider — use that one.";
  if (e instanceof Error && e.message) return e.message;
  return "Sign-in failed — please try again.";
}

export const AuthComponent = ({
  logo = <DefaultLogo />,
  brandName = "AndroLLM",
  redirectTo = "/dashboard",
  onAuthenticated,
}: AuthComponentProps) => {
  const [status, setStatus] = useState<AuthStatus>("idle");
  const [provider, setProvider] = useState<"google" | "github" | null>(null);
  const [redirecting, setRedirecting] = useState(false);
  const [errorMessage, setErrorMessage] = useState("");
  const confettiRef = useRef<ConfettiRef>(null);
  // Synchronous in-flight guard: React state updates async, so two clicks in
  // the same tick would otherwise both pass the status check → two popups.
  const busyRef = useRef(false);

  const celebrate = () => {
    const fire = confettiRef.current?.fire;
    if (!fire) return;
    const defaults = { startVelocity: 30, spread: 360, ticks: 60, zIndex: 100 };
    const particleCount = 50;
    fire({ ...defaults, particleCount, origin: { x: 0, y: 1 }, angle: 60 });
    fire({ ...defaults, particleCount, origin: { x: 1, y: 1 }, angle: 120 });
  };

  const finishSuccess = () => {
    setStatus("success");
    celebrate();
    window.setTimeout(() => {
      if (onAuthenticated) onAuthenticated();
      else window.location.assign(redirectTo);
    }, 1400);
  };

  const signIn = async (which: "google" | "github") => {
    if (busyRef.current) return;
    busyRef.current = true;
    setProvider(which);
    setRedirecting(false);
    setErrorMessage("");
    setStatus("working");
    try {
      if (which === "google") await signInWithGoogle();
      else await signInWithGitHub();
      finishSuccess();
    } catch (e) {
      busyRef.current = false;
      const code = typeof e === "object" && e !== null ? (e as { code?: unknown }).code : undefined;
      if (code === "auth/popup-blocked") {
        // Brave / popup blockers: fall back to full-page redirect instead.
        setRedirecting(true);
        try {
          await signInWithRedirectFallback(which);
        } catch (redirectError) {
          setRedirecting(false);
          setErrorMessage(friendlyAuthError(redirectError));
          setStatus("error");
        }
        return;
      }
      setErrorMessage(friendlyAuthError(e));
      setStatus("error");
    }
  };

  const reset = () => {
    busyRef.current = false;
    setStatus("idle");
    setProvider(null);
    setRedirecting(false);
    setErrorMessage("");
  };

  return (
    <div className="relative flex min-h-screen w-full flex-col items-center justify-center overflow-hidden bg-black py-24">
      <style>{`
            .glass-button-wrap { --anim-time: 400ms; --anim-ease: cubic-bezier(0.25, 1, 0.5, 1); --border-width: clamp(1px, 0.0625em, 4px); position: relative; z-index: 2; transform-style: preserve-3d; transition: transform var(--anim-time) var(--anim-ease); }
            .glass-button-shadow { --shadow-cutoff-fix: 2em; position: absolute; width: calc(100% + var(--shadow-cutoff-fix)); height: calc(100% + var(--shadow-cutoff-fix)); top: calc(0% - var(--shadow-cutoff-fix) / 2); left: calc(0% - var(--shadow-cutoff-fix) / 2); filter: blur(clamp(2px, 0.125em, 12px)); transition: filter var(--anim-time) var(--anim-ease); pointer-events: none; z-index: 0; }
            .glass-button-shadow::after { content: ""; position: absolute; inset: 0; border-radius: 9999px; background: linear-gradient(180deg, oklch(from #fff l c h / 20%), oklch(from #fff l c h / 10%)); width: calc(100% - var(--shadow-cutoff-fix) - 0.25em); height: calc(100% - var(--shadow-cutoff-fix) - 0.25em); top: calc(var(--shadow-cutoff-fix) - 0.5em); left: calc(var(--shadow-cutoff-fix) - 0.875em); padding: 0.125em; box-sizing: border-box; mask: linear-gradient(#000 0 0) content-box, linear-gradient(#000 0 0); mask-composite: exclude; transition: all var(--anim-time) var(--anim-ease); opacity: 1; }
            .glass-button { -webkit-tap-highlight-color: transparent; backdrop-filter: blur(clamp(1px, 0.125em, 4px)); transition: all var(--anim-time) var(--anim-ease); background: linear-gradient(-75deg, oklch(from #000 l c h / 5%), oklch(from #000 l c h / 20%), oklch(from #000 l c h / 5%)); box-shadow: inset 0 0.125em 0.125em oklch(from #fff l c h / 5%), inset 0 -0.125em 0.125em oklch(from #000 l c h / 50%), 0 0.25em 0.125em -0.125em oklch(from #fff l c h / 20%), 0 0 0.1em 0.25em inset oklch(from #000 l c h / 20%), 0 0 0 0 oklch(from #000 l c h); }
            .glass-button:hover { transform: scale(0.975); backdrop-filter: blur(0.01em); box-shadow: inset 0 0.125em 0.125em oklch(from #fff l c h / 5%), inset 0 -0.125em 0.125em oklch(from #000 l c h / 50%), 0 0.15em 0.05em -0.1em oklch(from #fff l c h / 25%), 0 0 0.05em 0.1em inset oklch(from #000 l c h / 50%), 0 0 0 0 oklch(from #000 l c h); }
            .glass-button-text { color: oklch(from #fff l c h / 90%); text-shadow: 0em 0.25em 0.05em oklch(from #fff l c h / 10%); transition: all var(--anim-time) var(--anim-ease); }
            .glass-button:hover .glass-button-text { text-shadow: 0.025em 0.025em 0.025em oklch(from #fff l c h / 12%); }
            .glass-button::after { content: ""; position: absolute; z-index: 1; inset: 0; border-radius: 9999px; width: calc(100% + var(--border-width)); height: calc(100% + var(--border-width)); top: calc(0% - var(--border-width) / 2); left: calc(0% - var(--border-width) / 2); padding: var(--border-width); box-sizing: border-box; background: conic-gradient(from var(--angle-1, -75deg) at 50% 50%, oklch(from #fff l c h / 50%) 0%, transparent 5% 40%, oklch(from #fff l c h / 50%) 50%, transparent 60% 95%, oklch(from #fff l c h / 50%) 100%), linear-gradient(180deg, oklch(from #000 l c h / 50%), oklch(from #000 l c h / 50%)); mask: linear-gradient(#000 0 0) content-box, linear-gradient(#000 0 0); mask-composite: exclude; transition: all var(--anim-time) var(--anim-ease); box-shadow: inset 0 0 0 calc(var(--border-width) / 2) oklch(from #000 l c h / 50%); pointer-events: none; }
            .glass-button-wrap:active .glass-button-text { text-shadow: 0.025em 0.25em 0.05em oklch(from #fff l c h / 12%); }
            @media (hover: none) and (pointer: coarse) { .glass-button::after { --angle-1: -75deg; } }
        `}</style>

      <Confetti ref={confettiRef} manualstart className="pointer-events-none fixed left-0 top-0 z-[999] h-full w-full" />

      <div className="absolute inset-0 z-0 opacity-60">
        <GradientBackground />
      </div>
      <div className="absolute inset-0 z-[1] bg-gradient-to-b from-black/60 via-transparent to-black" aria-hidden />
      <div className="relative z-10 mx-auto flex w-full max-w-[400px] flex-col items-center gap-6 p-4 text-center">
        <BlurFade className="flex items-center gap-2">
          <span className="flex items-center gap-2">
            {logo}
            <span className="text-base font-bold text-white">{brandName}</span>
          </span>
        </BlurFade>
          <BlurFade className="w-full">
            <div className="text-center">
              <p className="whitespace-nowrap text-4xl font-light tracking-tight text-white sm:text-5xl">
                Welcome back
              </p>
            </div>
          </BlurFade>
          <BlurFade delay={0.1} className="w-full text-center">
            <TextLoop interval={2.2} className="text-sm font-medium text-white/60">
              <span>Private AI on your device</span>
              <span>Your models, your dashboard</span>
              <span>Sign in to continue</span>
            </TextLoop>
          </BlurFade>

          <BlurFade delay={0.2} className="flex w-full flex-col items-stretch gap-3">
            <GlassButton
              size="sm"
              contentClassName="flex items-center justify-center gap-2"
              onClick={() => void signIn("google")}
              disabled={status === "working"}
              aria-label="Continue with Google"
            >
              {status === "working" && provider === "google" ? (
                <Loader className="h-5 w-5 animate-spin text-white/80" />
              ) : (
                <GoogleIcon />
              )}
              <span className="font-semibold text-white">Google</span>
            </GlassButton>
            <GlassButton
              size="sm"
              contentClassName="flex items-center justify-center gap-2"
              onClick={() => void signIn("github")}
              disabled={status === "working"}
              aria-label="Continue with GitHub"
            >
              {status === "working" && provider === "github" ? (
                <Loader className="h-5 w-5 animate-spin text-white/80" />
              ) : (
                <GitHubIcon />
              )}
              <span className="font-semibold text-white">GitHub</span>
            </GlassButton>
          </BlurFade>

          <div className="flex min-h-[3.5rem] w-full flex-col items-center justify-start gap-2" aria-live="polite">
            {status === "working" && (
              <p className="flex items-center gap-2 text-sm text-white/60">
                <Loader className="h-4 w-4 animate-spin" />
                {redirecting
                  ? "Popup was blocked — redirecting to provider…"
                  : provider === "google"
                    ? "Waiting for Google…"
                    : "Waiting for GitHub…"}
              </p>
            )}
            {status === "success" && (
              <p className="flex items-center gap-2 text-sm font-medium text-emerald-400">
                <PartyPopper className="h-4 w-4" />
                Signed in — taking you to your dashboard…
              </p>
            )}
            {status === "error" && (
              <>
                <p className="flex items-center gap-2 text-center text-sm text-rose-400">
                  <AlertCircle className="h-4 w-4 shrink-0" />
                  {errorMessage}
                </p>
                <button
                  type="button"
                  onClick={reset}
                  className="text-xs font-medium text-white/60 underline-offset-4 hover:text-white hover:underline"
                >
                  Try again
                </button>
              </>
            )}
            {status === "idle" && (
              <p className="text-center text-xs leading-relaxed text-white/40">
                Same account as your Android app.
                <br />
                No email sign-in — Google or GitHub only.
              </p>
            )}
          </div>
        </div>
    </div>
  );
};
