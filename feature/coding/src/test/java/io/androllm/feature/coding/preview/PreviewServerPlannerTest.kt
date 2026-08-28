package io.androllm.feature.coding.preview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the preview server planner: the preview must be served over a real
 * local HTTP server (never file://), choosing python3/node for static sites and
 * the project's own dev command for framework projects.
 */
class PreviewServerPlannerTest {

    private fun target(
        kind: PreviewDetector.PreviewKind,
        path: String = ""
    ): PreviewDetector.PreviewTarget = PreviewDetector.PreviewTarget(
        kind = kind,
        relativePath = path,
        url = null,
        framework = "test",
        title = "test target",
        priority = 50
    )

    private val fixedPort = { 8123 }

    // ── static sites ─────────────────────────────────────────────────────────

    @Test
    fun `static site prefers python http server serving the file's directory`() {
        val plan = PreviewServerPlanner.plan(
            target = target(PreviewDetector.PreviewKind.STATIC_FILE, "index.html"),
            installedAddons = setOf("python"),
            portProvider = fixedPort
        )
        assertNotNull(plan)
        assertEquals("python3 -m http.server 8123 --bind 0.0.0.0", plan!!.command)
        assertEquals("", plan.workingDir) // index.html lives at the workspace root
        assertNull(plan.requiredAddonId)
    }

    @Test
    fun `build output serves the output directory`() {
        val plan = PreviewServerPlanner.plan(
            target = target(PreviewDetector.PreviewKind.BUILD_OUTPUT, "dist/index.html"),
            installedAddons = setOf("python"),
            portProvider = fixedPort
        )
        assertNotNull(plan)
        assertEquals("dist", plan!!.workingDir)
        assertTrue(plan.command.contains("8123"))
    }

    @Test
    fun `static site falls back to node http-server when python is missing`() {
        val plan = PreviewServerPlanner.plan(
            target = target(PreviewDetector.PreviewKind.STATIC_FILE, "index.html"),
            installedAddons = setOf("nodejs"),
            portProvider = fixedPort
        )
        assertNotNull(plan)
        assertTrue(plan!!.command.startsWith("npx -y http-server"))
        assertNull(plan.requiredAddonId)
    }

    @Test
    fun `static site with no runtime flags the python addon as required`() {
        val plan = PreviewServerPlanner.plan(
            target = target(PreviewDetector.PreviewKind.STATIC_FILE, "index.html"),
            installedAddons = emptySet(),
            portProvider = fixedPort
        )
        assertNotNull(plan)
        assertEquals("python", plan!!.requiredAddonId)
    }

    // ── dev servers ──────────────────────────────────────────────────────────

    @Test
    fun `dev command target uses the project's dev script`() {
        val plan = PreviewServerPlanner.plan(
            target = target(PreviewDetector.PreviewKind.DEV_COMMAND),
            devCommands = listOf("npm run dev"),
            installedAddons = setOf("nodejs"),
            portProvider = fixedPort
        )
        assertNotNull(plan)
        assertEquals("npm run dev", plan!!.command)
        assertNull(plan.requiredAddonId)
        assertTrue(plan.description.contains("npm run dev"))
    }

    @Test
    fun `next dev is planned for next projects`() {
        val plan = PreviewServerPlanner.plan(
            target = target(PreviewDetector.PreviewKind.DEV_COMMAND),
            devCommands = listOf("npm run dev"),
            installedAddons = setOf("nodejs"),
            portProvider = fixedPort
        )
        assertEquals("npm run dev", plan?.command)
    }

    @Test
    fun `python dev server flags python addon when missing`() {
        val plan = PreviewServerPlanner.plan(
            target = target(PreviewDetector.PreviewKind.DEV_COMMAND),
            devCommands = listOf("python manage.py runserver"),
            installedAddons = emptySet(),
            portProvider = fixedPort
        )
        assertNotNull(plan)
        assertEquals("python", plan!!.requiredAddonId)
    }

    @Test
    fun `dev command target without any dev command yields no plan`() {
        val plan = PreviewServerPlanner.plan(
            target = target(PreviewDetector.PreviewKind.DEV_COMMAND),
            devCommands = emptyList(),
            installedAddons = setOf("nodejs"),
            portProvider = fixedPort
        )
        assertNull(plan)
    }

    // ── no-ops ───────────────────────────────────────────────────────────────

    @Test
    fun `running dev server needs no plan`() {
        val plan = PreviewServerPlanner.plan(
            target = target(PreviewDetector.PreviewKind.DEV_SERVER),
            installedAddons = setOf("nodejs"),
            portProvider = fixedPort
        )
        assertNull(plan)
    }

    @Test
    fun `none target yields no plan`() {
        assertNull(
            PreviewServerPlanner.plan(
                target = target(PreviewDetector.PreviewKind.NONE),
                portProvider = fixedPort
            )
        )
    }

    // ── port allocation ──────────────────────────────────────────────────────

    @Test
    fun `freePort returns a usable port`() {
        val port = PreviewServerPlanner.freePort()
        assertTrue(port in 1024..65535)
    }

    // ── reachability ─────────────────────────────────────────────────────────

    @Test
    fun `reachability check returns false for a closed port instead of throwing`() {
        // Port 1 is essentially never listening; the check must fail gracefully.
        assertEquals(false, HttpReachability.check("http://127.0.0.1:1/", timeoutMs = 300))
    }
}
