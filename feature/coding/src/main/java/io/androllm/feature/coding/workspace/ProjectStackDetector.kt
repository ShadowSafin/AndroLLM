package io.androllm.feature.coding.workspace

/**
 * Heuristic project understanding: detects the stack, entry points and the
 * canonical build/test/dev commands from manifest files. Pure function over two
 * lambdas (exists / read-head) so it runs on the JVM against any file source.
 */
data class StackReport(
    val stacks: List<String>,
    val entryPoints: List<String>,
    val buildCommands: List<String>,
    val devCommands: List<String>,
    val testCommands: List<String>
) {
    val isEmpty: Boolean get() = stacks.isEmpty() && entryPoints.isEmpty()

    fun render(): String = buildString {
        if (stacks.isNotEmpty()) append("Detected stack: ").append(stacks.joinToString(", ")).append('\n')
        if (entryPoints.isNotEmpty()) append("Entry points: ").append(entryPoints.joinToString(", ")).append('\n')
        if (buildCommands.isNotEmpty()) append("Build: ").append(buildCommands.joinToString(", ")).append('\n')
        if (devCommands.isNotEmpty()) append("Dev server: ").append(devCommands.joinToString(", ")).append('\n')
        if (testCommands.isNotEmpty()) append("Tests: ").append(testCommands.joinToString(", ")).append('\n')
    }.trimEnd()
}

object ProjectStackDetector {

    private const val HEAD_CHARS = 12_000

    /**
     * Inspects well-known manifest files. [exists] and [readHead] are workspace
     * scoped readers (readHead returns at most a few KB of the file, or null).
     */
    fun detect(exists: (String) -> Boolean, readHead: (String) -> String?): StackReport {
        val stacks = mutableListOf<String>()
        val entryPoints = mutableListOf<String>()
        val build = mutableListOf<String>()
        val dev = mutableListOf<String>()
        val test = mutableListOf<String>()

        // ── Node / JavaScript family ─────────────────────────────────────────
        val pkg = if (exists("package.json")) readHead("package.json") else null
        if (pkg != null) {
            stacks += "Node.js"
            val lower = pkg.lowercase()
            fun hasDep(name: String) = lower.contains("\"$name\"")
            when {
                hasDep("next") -> stacks += "Next.js"
                hasDep("nuxt") -> stacks += "Nuxt"
                hasDep("astro") -> stacks += "Astro"
                hasDep("@sveltejs/kit") -> stacks += "SvelteKit"
                hasDep("svelte") -> stacks += "Svelte"
                hasDep("vue") -> stacks += "Vue"
                hasDep("react") -> stacks += "React"
                hasDep("angular") || hasDep("@angular/core") -> stacks += "Angular"
                hasDep("express") -> stacks += "Express"
                hasDep("fastify") -> stacks += "Fastify"
                hasDep("electron") -> stacks += "Electron"
            }
            if (hasDep("vite")) stacks += "Vite"
            if (hasDep("tailwindcss")) stacks += "Tailwind CSS"
            if (hasDep("typescript")) stacks += "TypeScript"

            // Scripts → canonical commands.
            if (Regex("\"build\"\\s*:").containsMatchIn(pkg)) build += "npm run build"
            if (Regex("\"test\"\\s*:").containsMatchIn(pkg)) test += "npm test"
            if (Regex("\"lint\"\\s*:").containsMatchIn(pkg)) build += "npm run lint"
            when {
                Regex("\"dev\"\\s*:").containsMatchIn(pkg) -> dev += "npm run dev"
                Regex("\"start\"\\s*:").containsMatchIn(pkg) -> dev += "npm start"
                Regex("\"serve\"\\s*:").containsMatchIn(pkg) -> dev += "npm run serve"
            }
            // Entry points.
            Regex("\"main\"\\s*:\\s*\"([^\"]+)\"").find(pkg)?.groupValues?.get(1)?.let { entryPoints += it }
            Regex("\"module\"\\s*:\\s*\"([^\"]+)\"").find(pkg)?.groupValues?.get(1)?.let { entryPoints += it }
        }

        // ── Python ───────────────────────────────────────────────────────────
        val hasPyProject = exists("pyproject.toml")
        val hasRequirements = exists("requirements.txt")
        if (hasPyProject || hasRequirements || exists("setup.py") || exists("manage.py")) {
            stacks += "Python"
            val pyHead = (if (hasPyProject) readHead("pyproject.toml") else null)
                ?: (if (hasRequirements) readHead("requirements.txt") else null)
                ?: ""
            val lower = pyHead.lowercase()
            if (lower.contains("django")) stacks += "Django"
            if (lower.contains("flask")) stacks += "Flask"
            if (lower.contains("fastapi")) stacks += "FastAPI"
            build += "pip install -r requirements.txt"
            if (exists("manage.py")) {
                dev += "python manage.py runserver"
                entryPoints += "manage.py"
            }
            for (candidate in listOf("main.py", "app.py", "src/main.py")) {
                if (exists(candidate)) entryPoints += candidate
            }
        }

        // ── JVM / Android ────────────────────────────────────────────────────
        if (exists("build.gradle.kts") || exists("build.gradle") || exists("settings.gradle.kts") || exists("settings.gradle")) {
            val gradleHead = readHead("build.gradle.kts") ?: readHead("build.gradle") ?: ""
            val settings = readHead("settings.gradle.kts") ?: readHead("settings.gradle") ?: ""
            val combined = (gradleHead + settings).lowercase()
            stacks += if (combined.contains("com.android") || exists("app/build.gradle.kts") || exists("app/build.gradle")) {
                "Android (Gradle)"
            } else {
                "JVM (Gradle)"
            }
            build += "./gradlew build"
            test += "./gradlew test"
        } else if (exists("pom.xml")) {
            stacks += "Java (Maven)"
            build += "mvn package"
            test += "mvn test"
        }

        // ── Other ecosystems ─────────────────────────────────────────────────
        if (exists("go.mod")) {
            stacks += "Go"
            build += "go build ./..."
            test += "go test ./..."
        }
        if (exists("Cargo.toml")) {
            stacks += "Rust"
            build += "cargo build"
            test += "cargo test"
        }
        if (exists("Gemfile")) stacks += "Ruby"
        if (exists("composer.json")) stacks += "PHP"
        if (exists("Dockerfile")) stacks += "Docker"

        // ── Static web ───────────────────────────────────────────────────────
        if (exists("index.html")) {
            if (pkg == null) stacks += "Static HTML/CSS/JS"
            entryPoints += "index.html"
        }

        return StackReport(
            stacks = stacks.distinct(),
            entryPoints = entryPoints.distinct().take(6),
            buildCommands = build.distinct(),
            devCommands = dev.distinct(),
            testCommands = test.distinct()
        )
    }
}
