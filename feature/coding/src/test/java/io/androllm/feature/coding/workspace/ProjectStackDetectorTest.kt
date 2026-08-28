package io.androllm.feature.coding.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for heuristic project-stack detection (workspace_summary brain). */
class ProjectStackDetectorTest {

    private fun detectOver(files: Map<String, String>): StackReport =
        ProjectStackDetector.detect(
            exists = { it in files },
            readHead = { files[it] }
        )

    @Test
    fun `detects react vite typescript project with scripts`() {
        val pkg = """
            {
              "name": "demo",
              "main": "src/index.tsx",
              "scripts": { "dev": "vite", "build": "tsc && vite build", "test": "vitest", "lint": "eslint ." },
              "dependencies": { "react": "^18.0.0" },
              "devDependencies": { "vite": "^5.0.0", "typescript": "^5.0.0", "tailwindcss": "^3.0.0" }
            }
        """.trimIndent()
        val report = detectOver(mapOf("package.json" to pkg, "index.html" to "<html></html>"))

        assertTrue(report.stacks.contains("Node.js"))
        assertTrue(report.stacks.contains("React"))
        assertTrue(report.stacks.contains("Vite"))
        assertTrue(report.stacks.contains("TypeScript"))
        assertTrue(report.stacks.contains("Tailwind CSS"))
        // index.html with a package.json is not "static"
        assertFalse(report.stacks.contains("Static HTML/CSS/JS"))
        assertTrue(report.entryPoints.contains("src/index.tsx"))
        assertTrue(report.entryPoints.contains("index.html"))
        assertEquals(listOf("npm run dev"), report.devCommands)
        assertTrue(report.buildCommands.contains("npm run build"))
        assertTrue(report.buildCommands.contains("npm run lint"))
        assertEquals(listOf("npm test"), report.testCommands)
    }

    @Test
    fun `detects nextjs and prefers npm start when no dev script`() {
        val pkg = """
            { "dependencies": { "next": "14.0.0" }, "scripts": { "start": "next start", "build": "next build" } }
        """.trimIndent()
        val report = detectOver(mapOf("package.json" to pkg))
        assertTrue(report.stacks.contains("Next.js"))
        assertEquals(listOf("npm start"), report.devCommands)
    }

    @Test
    fun `detects django python project`() {
        val report = detectOver(
            mapOf(
                "requirements.txt" to "Django==5.0\nrequests\n",
                "manage.py" to "#!/usr/bin/env python"
            )
        )
        assertTrue(report.stacks.contains("Python"))
        assertTrue(report.stacks.contains("Django"))
        assertTrue(report.devCommands.contains("python manage.py runserver"))
        assertTrue(report.entryPoints.contains("manage.py"))
        assertTrue(report.buildCommands.contains("pip install -r requirements.txt"))
    }

    @Test
    fun `detects android gradle project`() {
        val report = detectOver(
            mapOf(
                "build.gradle.kts" to "plugins { id(\"com.android.application\") version \"8.0.0\" }",
                "settings.gradle.kts" to "include(\":app\")"
            )
        )
        assertTrue(report.stacks.contains("Android (Gradle)"))
        assertTrue(report.buildCommands.contains("./gradlew build"))
        assertTrue(report.testCommands.contains("./gradlew test"))
    }

    @Test
    fun `detects plain jvm gradle project`() {
        val report = detectOver(
            mapOf("build.gradle.kts" to "plugins { kotlin(\"jvm\") version \"2.0.0\" }")
        )
        assertTrue(report.stacks.contains("JVM (Gradle)"))
        assertFalse(report.stacks.contains("Android (Gradle)"))
    }

    @Test
    fun `detects static html site`() {
        val report = detectOver(mapOf("index.html" to "<!doctype html><html></html>"))
        assertTrue(report.stacks.contains("Static HTML/CSS/JS"))
        assertTrue(report.entryPoints.contains("index.html"))
        assertTrue(report.buildCommands.isEmpty())
    }

    @Test
    fun `detects go rust and docker`() {
        val report = detectOver(
            mapOf(
                "go.mod" to "module example.com/x",
                "Cargo.toml" to "[package]\nname = \"x\"",
                "Dockerfile" to "FROM debian"
            )
        )
        assertTrue(report.stacks.contains("Go"))
        assertTrue(report.stacks.contains("Rust"))
        assertTrue(report.stacks.contains("Docker"))
        assertTrue(report.buildCommands.contains("go build ./..."))
        assertTrue(report.testCommands.contains("cargo test"))
    }

    @Test
    fun `empty workspace yields empty report`() {
        val report = detectOver(emptyMap())
        assertTrue(report.isEmpty)
        assertEquals("", report.render())
    }

    @Test
    fun `render lists each section`() {
        val report = detectOver(mapOf("package.json" to """{"scripts":{"dev":"vite","build":"vite build","test":"vitest"}}"""))
        val rendered = report.render()
        assertTrue(rendered.contains("Detected stack: Node.js"))
        assertTrue(rendered.contains("Build: npm run build"))
        assertTrue(rendered.contains("Dev server: npm run dev"))
        assertTrue(rendered.contains("Tests: npm test"))
    }
}
