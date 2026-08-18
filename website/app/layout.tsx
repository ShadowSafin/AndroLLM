import type { Metadata, Viewport } from "next";
import Script from "next/script";
import { site } from "@/lib/site";
import { ThemeProvider } from "@/components/theme-provider";
import { Navbar } from "@/components/layout/navbar";
import { Footer } from "@/components/layout/footer";
import { PageTransition } from "@/components/page-transition";
import { TextCascade } from "@/components/gsap/text-cascade";
import { ScrollProgress } from "@/components/motion/parallax";
import { JsonLd } from "@/components/json-ld";

import "@fontsource/public-sans/400.css";
import "@fontsource/public-sans/500.css";
import "@fontsource/public-sans/600.css";
import "@fontsource/public-sans/700.css";
import "@fontsource/fraunces/500.css";
import "@fontsource/fraunces/600.css";
import "@fontsource/fraunces/700.css";
import "@fontsource/jetbrains-mono/400.css";
import "@fontsource/jetbrains-mono/500.css";
import "@fontsource/jetbrains-mono/600.css";
import "./globals.css";

export const metadata: Metadata = {
  metadataBase: new URL(site.url),
  title: {
    default: `${site.name} — ${site.tagline}`,
    template: `%s · ${site.name}`,
  },
  description: site.description,
  applicationName: site.name,
  keywords: [
    "AndroLLM",
    "Android AI",
    "local LLM",
    "LiteRT-LM",
    "litertlm",
    "on-device AI",
    "offline AI",
    "AI agent Android",
    "voice assistant offline",
    "MCP client Android",
  ],
  authors: [{ name: "AndroLLM", url: site.repo }],
  creator: "AndroLLM",
  publisher: "AndroLLM",
  alternates: { canonical: site.url },
  openGraph: {
    type: "website",
    url: site.url,
    siteName: site.name,
    title: `${site.name} — ${site.tagline}`,
    description: site.description,
    images: [{ url: "/images/logo.png", width: 512, height: 512, alt: `${site.name} logo` }],
    locale: "en_US",
  },
  twitter: {
    card: "summary_large_image",
    title: `${site.name} — ${site.tagline}`,
    description: site.description,
    images: ["/images/logo.png"],
  },
  robots: {
    index: true,
    follow: true,
    googleBot: { index: true, follow: true, "max-image-preview": "large", "max-snippet": -1 },
  },
  icons: {
    icon: "/images/app-icon.png",
    apple: "/images/app-icon.png",
  },
  manifest: "/manifest.webmanifest",
  category: "technology",
};

export const viewport: Viewport = {
  themeColor: [
    { media: "(prefers-color-scheme: light)", color: "#F5F4ED" },
    { media: "(prefers-color-scheme: dark)", color: "#141414" },
  ],
  width: "device-width",
  initialScale: 1,
};

const jsonLd = {
  "@context": "https://schema.org",
  "@type": "SoftwareApplication",
  name: site.name,
  applicationCategory: "UtilitiesApplication",
  operatingSystem: "Android",
  description: site.description,
  url: site.url,
  sameAs: [site.repo, site.discussions],
  softwareVersion: site.version,
  license: site.license,
  offers: { "@type": "Offer", price: "0", priceCurrency: "USD" },
  featureList: [
    "Local .litertlm model inference on LiteRT-LM",
    "CPU (XNNPACK) and OpenCL GPU acceleration with automatic fallback",
    "Offline voice assistant — wake word, ASR, TTS",
    "AI agent platform with 50+ tools",
    "Persistent memory with vector embeddings",
    "MCP server integration",
    "LiteLLM-compatible cloud providers",
  ],
  requirements: "Android 9 (API 28)+, arm64-v8a, 4 GB RAM minimum",
};

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="en" suppressHydrationWarning>
      <body
        className="grain min-h-screen font-sans antialiased"
        style={
          {
            "--font-public-sans": "'Public Sans', system-ui, sans-serif",
            "--font-fraunces": "'Fraunces', Georgia, serif",
            "--font-jetbrains": "'JetBrains Mono', monospace",
          } as React.CSSProperties
        }
      >
        <Script src="https://www.googletagmanager.com/gtag/js?id=G-LZ1H7X4BYD" strategy="afterInteractive" />
        <Script id="google-analytics" strategy="afterInteractive">
          {`
            window.dataLayer = window.dataLayer || [];
            function gtag(){dataLayer.push(arguments);}
            gtag('js', new Date());
            gtag('config', 'G-LZ1H7X4BYD');
          `}
        </Script>
        <ThemeProvider attribute="class" defaultTheme="dark" enableSystem disableTransitionOnChange>
          <a
            href="#main"
            className="sr-only focus:not-sr-only focus:fixed focus:left-4 focus:top-4 focus:z-[100] focus:rounded-pill focus:bg-[var(--accent)] focus:px-5 focus:py-2.5 focus:text-sm focus:font-semibold focus:text-[var(--accent-contrast)]"
          >
            Skip to content
          </a>
          <Navbar />
          <PageTransition>
            <TextCascade>{children}</TextCascade>
          </PageTransition>
          <Footer />
          <JsonLd data={jsonLd} />
          <ScrollProgress />
        </ThemeProvider>
      </body>
    </html>
  );
}