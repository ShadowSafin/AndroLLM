# Cloud Pipeline: Tool Calling, Usage Dashboard & Prompt Caching

This document describes the cloud request pipeline added on top of the
LiteLLM gateway. The local GGUF engine and the core app architecture are
untouched — everything here lives in `core:cloud`, `core:tools` (cloud tool
routing), and `feature:cloud` (dashboard UI).

**See also:** [Cloud Providers Guide](cloud-providers.md) (provider setup,
keys, health, streaming basics) · [FAQ](../FAQ.md#cloud-tools-usage--caching)
(user-facing Q&A) · [Testing Guide](../TESTING.md) (test patterns).

## Pipeline shape

```
User request
  → Prompt assembly          (chat layer)
  → Request validation       (CloudRequestValidator)
  → Cache lookup             (CloudRequestPlanner + PromptCache)
  → Tool planning            (native tools array / CloudToolRouter plan)
  → Provider selection       (ProviderManager + fallback chain)
  → Cloud request            (LiteLLMClient, streaming SSE)
  → Result observation       (CloudResultObserver)
  → Tool result handling     (ToolRunCoordinator / CloudToolRouter)
  → Final answer             (chat layer)
  → Usage logging            (CloudUsageMeter)
```

Every stage is failure-isolated: a broken cache file, a failed usage write,
or a dead provider degrades gracefully instead of crashing the request.

## Key components

| Component | Module | Responsibility |
|---|---|---|
| `CloudRequestValidator` | core:cloud | Rejects blank models, empty/malformed message lists, broken tool schemas, oversized payloads before they hit the network. |
| `CloudRequestPlanner` | core:cloud | Fingerprints the stable prefix (system prompt + tool schemas), consults the prompt cache, detects invalidation conditions, decorates the request with provider-aware cache hints. |
| `PromptCache` | core:cloud | LRU + TTL store of stable prompt prefixes with hit/miss/invalidation/savings diagnostics. Disk-backed, corruption-safe. |
| `CloudCacheHints` | core:cloud | Provider-aware caching: `cache_control` markers for Anthropic-family models, byte-stable prefixes for automatic prefix caching (OpenAI, Gemini, DeepSeek, ...). |
| `CloudResultObserver` | core:cloud | Folds the SSE event stream into a turn result: text, tool calls, usage, latency, first-token time, normalized error category. |
| `CloudFallbackToolParser` | core:cloud | Recovers tool calls from plain-text responses for providers that don't emit native `tool_calls`; strips raw syntax so it never reaches the UI. |
| `CloudUsageMeter` | core:cloud | Records every cloud request (chat, embedding) with tokens, cost estimate, latency, cache behavior, tool-call count; serves the dashboard. |
| `CloudToolRouter` | core:tools | Routes native + fallback-parsed tool calls through conditional gating and the hardened `ToolRunCoordinator` execution path. |
| `CloudConditionals` | core:tools | "If weather says rain → SMS", "if results found → email" evaluation against observed tool outputs. |
| `CloudUsageDashboardScreen` | feature:cloud | Usage control center: overview, tokens, cost, latency, provider health, tool calling, cache performance, limits/alerts, history. |

## Tool calling

Cloud models get the OpenAI-compatible `tools` array (native function
calling). The chat loop buffers streaming tool-call fragments, validates the
completed JSON, executes through the gated coordinator (argument validation,
confirmation for SMS/email/calls/calendar/device actions, loop guard), feeds
`role="tool"` results back, and repeats until the model answers in natural
language.

Extra guarantees added by this upgrade:

- **Fallback parsing** — when a provider/model writes the call into the
  answer text instead of emitting `tool_calls`, `CloudFallbackToolParser`
  recovers it (XML tags, fenced JSON, bare JSON envelopes, embedded-string
  arguments) and `stripToolSyntax` keeps raw JSON out of the chat UI.
- **Multi-step + conditional** — `CloudToolRouter` keeps an internal plan
  (`AgentPlanner`) and evaluates user conditions against observed tool
  outputs before executing dependent actions. A skipped action is fed back
  to the model as a normal tool result so the final answer explains it.
- **Provider fallback** — if the primary provider fails *before the first
  token* (rate limit, timeout, 5xx, transport), the same request is retried
  on the other enabled providers. Mid-stream failures still surface as
  `CloudException` so partial output is preserved.

## Usage dashboard

Settings → Cloud → **Cloud Usage Dashboard** (also reachable from the Cloud
Providers toolbar). Shows:

- current provider/model, requests, tokens (input/output/total), estimated
  cost, average latency, first-token latency, success/error rate, retries,
  rate-limit hits, active sessions, cache hits/misses, tool-call counts,
  last request status, today + month totals
- trends: daily tokens/cost/latency charts, requests-by-hour
- per-provider and per-model breakdowns, provider health + quota warnings
- alerts: error spikes, rate-limit spikes, fallback pressure
- filters (date range / provider / model), CSV export, clear, detailed
  request history

Cost figures are **estimates** from a built-in per-model price table
(`CloudPricing`) with a family heuristic for unknown models.

Records persist in `files/cloud/cloud-usage.json` (atomic writes, bounded
ring of 1000 records + 90 days of daily rollups + lifetime counters).
Usage accounting never throws into the request path.

## Prompt caching

The planner fingerprints the **stable prefix** of every request (system
messages + tool schemas — never user-private dynamic content):

- first turn of a prefix → cache **miss**, entry stored
- later turns with the same prefix → cache **hit**; the request is decorated
  so the provider can reuse its server-side cache:
  - Anthropic-family: `cache_control: {"type":"ephemeral"}` on the last
    system message (LiteLLM passes it through)
  - OpenAI/Gemini/DeepSeek/etc.: the prefix is kept byte-stable (trimmed,
    de-duplicated system messages) so automatic prefix caching applies
- streaming responses also report provider-side cached tokens
  (`usage.prompt_tokens_details.cached_tokens`) when available

Invalidation reasons (tracked and shown on the dashboard): system prompt
changed, tool schema changed, model changed, provider changed, conversation
reset, TTL expiry, corruption, manual clear.

Diagnostics: hits, misses, invalidations, saved tokens, estimated cost
saved, estimated latency reduction.

## Debugging

`CloudPipelineLogger` (tag `CloudPipeline`) logs metadata-only lines for:
planning decisions, cache hits/misses/invalidations, provider selection and
fallbacks, request start/finish with latency/tokens, tool routing steps,
usage recording, and failures. Message content and API keys are never
logged.

## Where things live

| Path | Contents |
|---|---|
| `core/cloud/.../usage/` | `CloudUsageModels`, `CloudPricing`, `CloudUsageStore` (file + in-memory), `CloudUsageMeter` |
| `core/cloud/.../cache/` | `PromptCacheModels`, `PromptCache`, `CloudCacheHints` |
| `core/cloud/.../pipeline/` | `CloudRequestValidator`, `CloudRequestPlanner`, `CloudResultObserver`, `CloudFallbackToolParser`, `CloudPipelineLogger` |
| `core/cloud/.../CloudGateway.kt` | Pipeline orchestration + provider fallback chain + usage recording |
| `core/tools/.../cloud/` | `CloudToolRouter`, `CloudConditionals` |
| `feature/cloud/` | `CloudUsageDashboardScreen`, `CloudUsageDashboardViewModel`, `CloudUsageCharts` |
| `files/cloud/cloud-usage.json` | Persisted usage state (atomic writes, corruption-safe) |
| `files/cloud/prompt-cache.json` | Persisted prompt-cache entries + stats |

## Testing

| Suite | Module | Covers |
|---|---|---|
| `CloudGatewayPipelineTest` | core:cloud | Fallback on 500/429, healthy-primary passthrough, cache hit on 2nd turn, all-providers-fail, validation rejection, chatOnce fallback, tool-call counting (MockWebServer primary + fallback) |
| `CloudUsageMeterTest`, `CloudPricingTest`, `CloudUsageMetricsTest` | core:cloud | Recording, aggregation, filters, alerts, snapshots, cost math, CSV export |
| `PromptCacheTest`, `CloudCacheHintsTest` | core:cloud | LRU/TTL, invalidation reasons, corruption quarantine, provider-aware decoration, prefix stabilization |
| `CloudRequestValidatorTest`, `CloudFallbackToolParserTest`, `CloudResultObserverTest`, `CloudRequestPlannerTest` | core:cloud | Validation rules, text→tool-call recovery, stream folding + error classification, plan/cache/invalidation flow |
| `CloudConditionalsTest`, `CloudToolRouterTest` | core:tools | Rain/results condition evaluation, phrase→action binding, skip feedback, guarded execution |
| `CloudUsageDashboardViewModelTest` | feature:cloud | Snapshot exposure, filters, cache stats, clear/export actions |

Test-environment notes (including the MockWebServer `retries = 0` pattern,
the init-before-record ordering rule, the relaxed-mock `FileProvider`
hazard, and the Windows worker-shutdown watchdog) are documented in the
[Testing Guide](../TESTING.md#cloud-pipeline-tests-gateway-cache-usage).
