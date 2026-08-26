# Cloud Providers Quick Guide

Quick reference for connecting cloud AI providers.

---

## What Are Cloud Providers?

Cloud providers let you use AI models hosted on remote servers when local inference isn't suitable (e.g., very large models, specialized capabilities). AndroLLM supports any provider compatible with the **LiteLLM proxy protocol** (OpenAI-compatible API).

---

## Supported Providers

| Provider | Base URL | Notes |
|---|---|---|
| OpenAI | `https://api.openai.com` | GPT-4, GPT-4o, GPT-3.5-turbo |
| Anthropic | Via LiteLLM proxy | Claude 3 Opus, Sonnet, Haiku |
| Google Gemini | Via LiteLLM proxy | Gemini 1.5 Pro, Flash |
| xAI Grok | `https://api.x.ai` | Grok-2 |
| Mistral | `https://api.mistral.ai` | Mistral Large, Small |
| Self-hosted LiteLLM | Your URL | Any model through your proxy |

---

## Adding a Provider

1. Go to **Settings → Cloud Providers**
2. Tap **Add Provider**
3. Enter:
   - **Name**: Display name (e.g., "OpenAI")
   - **Base URL**: API endpoint (e.g., `https://api.openai.com`)
   - **API Key**: Your secret key (encrypted locally)
4. Tap **Save**
5. The app will discover available models automatically

---

## Using Cloud Models

1. In the chat screen, tap the model selector
2. Choose a cloud model from your configured providers
3. Chat normally — responses stream in real-time

---

## Security

- API keys are encrypted with **AES-256/GCM** via Android Keystore
- Keys are decrypted only at request time — never logged
- You control which provider is active at any time
- Disable cloud mode entirely in Settings

---

## Provider Health

The app monitors provider health automatically:
- Periodic probes to `/health/liveliness`
- Latency tracking per provider
- Automatic failover to healthy providers
- Status shown in Cloud Providers screen

---

## Cloud Tool Calling

Cloud models can run tools on your device — search, weather, contacts,
calculator, SMS, email, calendar, notes, navigation and more:

- Requests use native function calling when the provider supports it, with
  an automatic fallback parser for providers that write tool calls as text
- Arguments are validated before execution; sensitive actions (SMS, email,
  calls, calendar, device operations) ask for confirmation first
- Multi-step conditional workflows work out of the box: *"check the weather
  and if it rains, message Mom"*

---

## Usage Dashboard

Track what your cloud usage does: **Settings → Cloud Usage Dashboard**
(or the chart icon on the Cloud Providers screen).

- Requests, tokens, estimated cost, latency, success/error rates
- Rate-limit hits, retries, provider fallbacks, cache performance
- Provider health + quota warnings, alerts, request history
- Date/provider/model filters and CSV export

Costs are estimates from a built-in price table — guidance, not billing.

---

## Prompt Caching

To cut cost and latency, AndroLLM keeps the **stable part** of your prompts
(system prompt + tool schemas) byte-stable and marks it for provider-side
caching where supported (Anthropic-style `cache_control` via LiteLLM, or
automatic prefix caching on OpenAI/Gemini/DeepSeek-family models). Your
personal messages are never cached. Hit/miss/savings stats appear in the
usage dashboard.

---

## Custom Providers

You can add any LiteLLM-compatible endpoint:

```
Name: My Company
Base URL: https://litellm.internal.example.com
API Key: sk-...
```

Custom models can be specified per-model with alternate keys or headers.

---

## See Also

- [Cloud Providers Architecture](cloud/cloud-providers.md) — Full technical deep dive
- [Cloud Pipeline](cloud/cloud-pipeline.md) — Tool calling, usage dashboard & prompt caching internals
- [README](../README.md) — Feature overview
