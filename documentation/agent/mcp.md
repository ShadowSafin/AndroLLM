# MCP Servers

Connect the assistant to **Model Context Protocol (MCP)** servers and import
their tools as first-class capabilities.

---

## Overview

MCP is an open protocol that lets AI applications discover and call tools
exposed by external servers. AndroLLM bundles the official Kotlin MCP SDK
(`io.modelcontextprotocol:kotlin-sdk`, pinned to 0.10.0 for compatibility with
the project's Kotlin toolchain) over a **Streamable HTTP** transport.

Remote tools are imported into the same `ToolRegistry` as built-in tools,
prefixed with the server name:

```
my-server/tool_name  →  mcp_my_server_tool_name
```

They go through **all** the same safety gates (permission toggle, timeout,
trace logging) and are offered to the planner exactly like native tools — so
an MCP server is a plug-in way to add unlimited future capabilities without
touching the app code.

---

## Architecture

```
┌────────────────────────────┐        ┌─────────────────────────────┐
│   McpSettingsStore         │        │   McpConnectionManager      │
│   (DataStore persistence)  │ ─────► │   • connect / disconnect    │
└────────────────────────────┘        │   • per-server state        │
                                      │   • idempotent reconnect    │
                                      └──────────────┬──────────────┘
                                                     │  listTools()
                                                     ▼
                                        ┌─────────────────────────────┐
                                        │   McpRemoteTool (per tool)  │
                                        │   JSON-schema → ToolSpec    │
                                        └──────────────┬──────────────┘
                                                       ▼
                                        ┌─────────────────────────────┐
                                        │   ToolRegistry              │
                                        │   mcp_<server>_<tool>       │
                                        └─────────────────────────────┘
```

| Component | Role |
|---|---|
| `McpServer` | Persisted model: id, name, url, optional bearer token, enabled |
| `McpSettingsStore` | DataStore-backed persistence of server list |
| `McpConnectionManager` | Lifecycle: connect (Streamable HTTP), list tools, import/remove tools, disconnect; per-server `State` (Offline / Connecting / Connected / Failed) |
| `McpRemoteTool` | Adapts a remote JSON-schema tool definition to the local `Tool` contract; `callTool` results become `ToolResult`s |

---

## Configuration

### Settings → MCP Servers

- **Add MCP Server** — name + URL (`https://…/mcp`) + optional bearer token
- Per-server **enable / disable** switch
- Live status line: `Connected • N tools`, `Connecting…`, or the failure reason
- **Remove** to delete a server and unregister its tools

### Connection

- Transport: MCP Streamable HTTP (SSE-capable)
- Per-server auth: optional `Authorization: Bearer <token>` header
- Reconnect is idempotent: enabling / toggling a server connects once and
  imports its tools; disabling disconnects and removes them

---

## Using Remote Tools

Remote tools behave exactly like built-ins:

1. Available to the planner (local GGUF and cloud) under `mcp_<server>_<tool>`
2. Gated by the same permission system (Settings → Automation) and execution
   timeout
3. Confirmation-gated if the server marks the tool high-risk
4. Recorded in the Tool Debug trace log
5. Fed back to the model as `ToolResult` summaries between rounds

---

## Security Notes

- Tokens are stored in the app's DataStore (private app storage); for the
  highest-value secrets the same Keystore-backed patterns used for cloud API
  keys can be applied
- Remote tools can never bypass the executor gates — the executor is the only
  code path that runs tool implementations
- Only connect to MCP servers you trust: a server's tool descriptions are
  model-visible and its tools run with your account's permissions on that
  server

---

## See Also

- [Agent Platform](agent-platform.md) — executor safety gates
- [Tool Catalog](tools.md) — built-in tools alongside MCP tools
- [Workflow Engine](workflow-engine.md) — MCP tools in multi-step tasks
