# Multi-stage production Dockerfile for the AndroLLM website (Next.js App Router).
#
# Coolify auto-detects this Dockerfile and uses it instead of its Nixpacks
# buildpack. Everything the runtime needs is pinned here:
#   - exact Node.js version
#   - exact build command (npm run build -> next build with output: "standalone")
#   - exact start command (node server.js from .next/standalone)
#   - port (EXPOSE 3000) and bind address (HOSTNAME=0.0.0.0)
# Coolify reads EXPOSE to configure its reverse proxy target, so the proxied
# port can never drift from the port the server actually listens on.

# ---- Stage 1: install dependencies ----
FROM node:22-alpine AS deps
WORKDIR /app
# Install production deps first so layer caching works and the final runtime
# image only contains what the app actually needs at runtime.
COPY package.json package-lock.json ./
RUN npm ci

# ---- Stage 2: build ----
FROM node:22-alpine AS builder
WORKDIR /app
COPY --from=deps /app/node_modules ./node_modules
COPY . .
ENV NEXT_TELEMETRY_DISABLED=1
RUN npm run build

# ---- Stage 3: runtime ----
FROM node:22-alpine AS runner
WORKDIR /app

ENV NODE_ENV=production
ENV NEXT_TELEMETRY_DISABLED=1
# Bind to all interfaces so the container's reverse proxy can reach the app
# (never 127.0.0.1, which is unreachable from outside the container).
ENV HOSTNAME=0.0.0.0
# Port used by the standalone server. Coolify overrides PORT at runtime; 3000
# is the default that matches EXPOSE below.
ENV PORT=3000

EXPOSE 3000

# Run as an unprivileged user.
RUN addgroup --system --gid 1001 nodejs \
  && adduser --system --uid 1001 nextjs

# Standalone output: self-contained server.js + traced node_modules.
COPY --from=builder /app/.next/standalone ./
# Static assets and public files are NOT traced into the standalone output;
# they must be copied explicitly or every asset 404s.
COPY --from=builder /app/.next/static ./.next/static
COPY --from=builder /app/public ./public

USER nextjs

# Health check against the homepage so orchestrators/proxies can verify the
# server is actually serving (Coolify performs its own check on EXPOSE too).
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
  CMD node -e "fetch('http://127.0.0.1:' + (process.env.PORT || 3000) + '/').then(r => process.exit(r.status === 200 ? 0 : 1)).catch(() => process.exit(1))"

CMD ["node", "server.js"]
