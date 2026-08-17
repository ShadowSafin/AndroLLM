# AndroLLM Website

The public website for [AndroLLM](https://github.com/ShadowSafin/AndroLLM) — a [Next.js](https://nextjs.org)
App Router site (self-hosted server, `output: "standalone"`), rendered in the project's
**Parchment Ledger** design system: parchment surfaces, ochre accents, serif headlines, and a
ledger-line grid.

## Quick commands

```bash
npm install      # install dependencies
npm run dev      # local dev server at http://localhost:3000
npm run build    # production build -> .next (standalone output in .next/standalone)
npm run start    # production server (after build) at http://localhost:3000
npx tsc --noEmit # typecheck only
```

There is no database and no analytics. All content is static — the pages never depend on a
backend, so the production server only needs to serve the pre-rendered HTML.

## Deployment (Coolify / Docker)

The repository ships a `Dockerfile` in this directory, so Coolify builds and runs the site
without any Nixpacks auto-detection:

- Multi-stage build on `node:22-alpine`; the final image only contains the
  `.next/standalone` output, `.next/static`, and `public/`.
- The server listens on `0.0.0.0:3000` (`EXPOSE 3000`, `PORT` overridable via environment).
  Coolify reads `EXPOSE` to configure its reverse proxy target.
- Runs as an unprivileged user with a built-in health check against `/`.

```bash
docker build -t androllm-website .
docker run --rm -p 3000:3000 androllm-website
```

In Coolify, point the application at this repo with base directory `/website`; the Dockerfile
is picked up automatically and no start command / port configuration is required.

## Structure

| Path | Purpose |
| --- | --- |
| `app/` | Route pages, sitemap/robots/manifest, metadata, 404 |
| `content/docs/` | The 33 documentation markdown files (shared with the repo) |
| `content/blog.ts` | Blog post content, typed as a data module |
| `lib/` | Site config (`site.ts`), docs parser, MDX plumbing |
| `components/` | Marketing UI, layout (navbar/footer), docs components |
| `styles/globals.css` | Full token system (light/dark) + base styles |
| `animations/` | Lightweight scroll-reveal primitives |

## How content stays in sync with the repository

- **Docs pages** (`/docs`) are rendered from the markdown files in this folder — the same files
  developers read in the repo's `documentation/` directory. One source of truth, zero drift.
- **Models page** is generated from curated data in the app's model catalog domain (same product
  data that seeds the app's library lookup).
- **Version, ABI, SDK levels, providers, and roadmap** all live in `lib/site.ts`, mirroring
  the repo's release stamps.
- **GitHub page & Contributors** fetch live numbers from the GitHub API in the browser and degrade
  gracefully (static fallbacks) when offline — the exported pages never depend on a backend.

## Design system

Tokens are defined in `:root` / `[data-theme="dark"]` in `styles/globals.css`:

| Token | Value (light) | Role |
| --- | --- | --- |
| `--bg` | `#FBF5E9` | Parchment ground |
| `--surface` | `#F4EAD6` | Cards |
| `--ink` | `#23211C` | Headings / body |
| `--accent` / `--accent-deep` | `#D97757` / `#B85C3F` | Ochre-brand actions |
| `--line` | `#E2D3B6` | Hairlines |
| `--ok` / `--warn` / `--danger` | `#4C7A5D` / `#B08A5C` / `#A1483E` | Semantic status |

The dark theme (`prefers-color-scheme` + manual toggle) re-bases surfaces to deep walnut
(`#1A1712`) while keeping parchment-toned ink for warmth. Type is a Fraunces + Inter pairing
loaded synchronously (self-hosted UI-format subsets, no runtime font fetch).