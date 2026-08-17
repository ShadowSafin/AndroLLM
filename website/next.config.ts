import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  // Self-hosted production build. Coolify (and other PaaS/Docker hosts) run
  // `.next/standalone/server.js`, so the container needs no dev tooling.
  output: "standalone",
  reactStrictMode: true,
  poweredByHeader: false,
  images: {
    formats: ["image/avif", "image/webp"],
    remotePatterns: [
      { protocol: "https", hostname: "avatars.githubusercontent.com" },
      { protocol: "https", hostname: "api.github.com" },
      { protocol: "https", hostname: "raw.githubusercontent.com" },
    ],
  },
};

export default nextConfig;