package io.androllm.feature.coding.tools.impl

/**
 * Heuristic detection of commands that start long-running servers
 * (`npm run dev`, `npm start`, watchers, HTTP servers...). Such commands never
 * exit on their own, so they are automatically run as background services
 * (see BackgroundServiceManager) instead of blocking until the timeout.
 *
 * Deliberately conservative: when unsure, the model can always pass
 * `background: true` explicitly. Pure function for unit tests.
 */
object ServerCommands {

    private val PATTERNS = listOf(
        // npm/pnpm/yarn/bun lifecycle scripts that conventionally start servers.
        Regex("""\bnpm\s+(?:run\s+)?(?:dev|start|serve|watch|preview)\b"""),
        Regex("""\b(?:pnpm|yarn|bun)\s+(?:run\s+)?(?:dev|start|serve|watch|preview)\b"""),
        // Direct dev-server CLIs.
        Regex("""\b(?:vite|next|nuxt|nodemon|ts-node-dev|webpack)\s+(?:dev|start|serve)\b"""),
        Regex("""\bnpx\s+\S*(?:vite|next|nuxt|astro|svelte-kit|serve)\b"""),
        // Node entrypoints that are conventionally servers.
        Regex("""\bnode\s+\S*(?:server|app|index|main)\S*\.(?:js|mjs|cjs|ts)\b"""),
        // Python servers.
        Regex("""\bpython3?\s+(?:-m\s+)?http\.server\b"""),
        Regex("""\bpython3?\s+\S*manage\.py\s+runserver\b"""),
        Regex("""\b(?:flask|uvicorn|gunicorn|daphne)\s+(?:run|main)?\b"""),
        Regex("""\bpython3?\s+-m\s+(?:uvicorn|flask|http\.server)\b"""),
        // Ruby/PHP quick servers.
        Regex("""\bphp\s+-S\b"""),
        Regex("""\bruby\s+-run\s+server\b"""),
        Regex("""\brails\s+s(?:erver)?\b""")
    )

    /** True when [command] looks like a long-running dev server / watcher. */
    fun looksLikeServer(command: String): Boolean {
        val trimmed = command.trim()
        return PATTERNS.any { it.containsMatchIn(trimmed) }
    }
}
