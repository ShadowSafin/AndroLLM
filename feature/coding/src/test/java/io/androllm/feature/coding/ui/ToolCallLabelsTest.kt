package io.androllm.feature.coding.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Labels for live tool cards: what the user sees the moment a tool starts. */
class ToolCallLabelsTest {

    @Test
    fun `run_command shows a precise status line plus the command`() {
        assertEquals(
            "Building project...\n$ npm run build",
            ToolCallLabels.describe("run_command", """{"command":"npm run build"}""")
        )
        assertEquals(
            "Starting local server...\n$ npm run dev   [background]",
            ToolCallLabels.describe("run_command", """{"command":"npm run dev","background":true}""")
        )
        assertEquals(
            "Running tests...\n$ pytest   (in tests)",
            ToolCallLabels.describe("run_command", """{"command":"pytest","working_dir":"tests"}""")
        )
    }

    @Test
    fun `command intent covers the common workflows`() {
        assertEquals("Installing dependencies...", ToolCallLabels.commandIntent("npm install"))
        assertEquals("Installing dependencies...", ToolCallLabels.commandIntent("pip install -r requirements.txt"))
        assertEquals("Installing dependencies...", ToolCallLabels.commandIntent("apt-get install -y nodejs"))
        assertEquals("Starting local server...", ToolCallLabels.commandIntent("npm run dev"))
        assertEquals("Starting local server...", ToolCallLabels.commandIntent("python3 -m http.server 8000"))
        assertEquals("Starting local server...", ToolCallLabels.commandIntent("python manage.py runserver"))
        assertEquals("Starting local server...", ToolCallLabels.commandIntent("npm start"))
        assertEquals("Running tests...", ToolCallLabels.commandIntent("npm test"))
        assertEquals("Running tests...", ToolCallLabels.commandIntent("./gradlew test"))
        assertEquals("Checking code quality...", ToolCallLabels.commandIntent("npm run lint"))
        assertEquals("Building project...", ToolCallLabels.commandIntent("./gradlew build"))
        assertEquals("Building project...", ToolCallLabels.commandIntent("vite build"))
        assertEquals(null, ToolCallLabels.commandIntent("ls -la"))
        assertEquals(null, ToolCallLabels.commandIntent(""))
    }

    @Test
    fun `install intent wins over other keywords`() {
        // "npm install && npm run build" is primarily an install step.
        assertEquals("Installing dependencies...", ToolCallLabels.commandIntent("npm install && npm run build"))
    }

    @Test
    fun `file tools show the path`() {
        assertEquals("Reading src/Main.kt", ToolCallLabels.describe("read_file", """{"path":"src/Main.kt"}"""))
        assertEquals(
            "Writing app.js (11 chars)",
            ToolCallLabels.describe("write_file", """{"path":"app.js","content":"console.log"}""")
        )
        assertEquals("Editing app.js", ToolCallLabels.describe("edit_file", """{"path":"app.js"}"""))
    }

    @Test
    fun `search and service tools have readable labels`() {
        assertEquals(
            "Searching \"TODO\" in src",
            ToolCallLabels.describe("grep", """{"pattern":"TODO","path":"src"}""")
        )
        assertEquals("Stopping svc-abc123", ToolCallLabels.describe("stop_background_service", """{"id":"svc-abc123"}"""))
        assertTrue(ToolCallLabels.describe("list_background_services", "{}").startsWith("Background services"))
    }

    @Test
    fun `announcing labels use verb-only phrasing`() {
        assertEquals("Writing file…", ToolCallLabels.announcing("write_file"))
        assertEquals("Reading file…", ToolCallLabels.announcing("read_file"))
        assertEquals("Editing file…", ToolCallLabels.announcing("edit_file"))
        assertEquals("Running command…", ToolCallLabels.announcing("run_command"))
        assertEquals("Searching codebase…", ToolCallLabels.announcing("grep"))
        assertEquals("Preparing mystery_tool…", ToolCallLabels.announcing("mystery_tool"))
    }

    @Test
    fun `malformed args fall back gracefully`() {
        assertEquals("$ ?", ToolCallLabels.describe("run_command", "not json"))
        assertEquals("Reading ?", ToolCallLabels.describe("read_file", ""))
        assertEquals("unknown_tool", ToolCallLabels.describe("unknown_tool", "{}"))
    }
}
