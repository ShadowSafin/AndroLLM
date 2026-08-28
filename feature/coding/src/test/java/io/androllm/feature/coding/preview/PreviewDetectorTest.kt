package io.androllm.feature.coding.preview

import io.androllm.feature.coding.environment.BackgroundServiceInfo
import org.junit.Assert.*
import org.junit.Test

class PreviewDetectorTest {

    private fun svc(url: String = "http://localhost:5173", cmd: String = "npm run dev"): BackgroundServiceInfo =
        BackgroundServiceInfo(
            id = "svc-abc123",
            command = cmd,
            running = true,
            statusLabel = "RUNNING",
            port = 5173,
            urlOnDevice = url,
            urlNetwork = "http://192.168.1.10:5173",
            startedAtMs = System.currentTimeMillis(),
            logFile = "/tmp/svc.log"
        )

    @Test
    fun `dev server running wins over static file`() {
        val files = mapOf("index.html" to "<html></html>", "package.json" to """{"scripts":{"dev":"vite"}}""")
        val result = PreviewDetector.detect(
            exists = { it in files },
            readHead = { files[it] },
            services = listOf(svc()),
            workspacePath = "/workspace/demo"
        )
        assertEquals(PreviewDetector.PreviewStatus.READY, result.status)
        assertEquals(PreviewDetector.PreviewKind.DEV_SERVER, result.target?.kind)
        assertTrue(result.autoOpen)
        assertTrue(result.logs.any { it.contains("preview target found: DEV_SERVER") })
        assertTrue(result.logs.any { it.contains("preview opened") })
    }

    @Test
    fun `static html detected when no server`() {
        val files = mapOf("index.html" to "<html>hello</html>")
        val result = PreviewDetector.detect(
            exists = { it in files },
            readHead = { files[it] },
            services = emptyList(),
            workspacePath = "/workspace/site"
        )
        assertEquals(PreviewDetector.PreviewStatus.READY, result.status)
        assertEquals(PreviewDetector.PreviewKind.STATIC_FILE, result.target?.kind)
        assertEquals("index.html", result.target?.relativePath)
        assertTrue(result.autoOpen)
        assertTrue(result.logs.any { it.contains("framework detected") })
        assertTrue(result.logs.any { it.contains("preview ready") })
    }

    @Test
    fun `build output dist index detected`() {
        val files = mapOf(
            "dist/index.html" to "<html>built</html>",
            "package.json" to """{"dependencies":{"vite":"5.0.0"}}"""
        )
        val result = PreviewDetector.detect(
            exists = { it in files },
            readHead = { files[it] },
            services = emptyList(),
            workspacePath = "/workspace/app"
        )
        assertEquals(PreviewDetector.PreviewStatus.READY, result.status)
        assertEquals(PreviewDetector.PreviewKind.BUILD_OUTPUT, result.target?.kind)
        assertEquals("dist/index.html", result.target?.relativePath)
    }

    @Test
    fun `react vite project with dev script but no server returns not available with suggestion`() {
        val pkg = """{"dependencies":{"react":"18","vite":"5"},"scripts":{"dev":"vite","build":"vite build"}}"""
        val files = mapOf("package.json" to pkg)
        val result = PreviewDetector.detect(
            exists = { it in files },
            readHead = { files[it] },
            services = emptyList(),
            workspacePath = "/workspace/react-app"
        )
        assertEquals(PreviewDetector.PreviewStatus.NOT_AVAILABLE, result.status)
        assertEquals(PreviewDetector.PreviewKind.DEV_COMMAND, result.target?.kind)
        assertFalse(result.autoOpen)
        assertNotNull(result.suggestion)
        assertTrue(result.suggestion!!.contains("npm run dev") || result.suggestion.contains("dev"))
        assertTrue(result.logs.any { it.contains("preview skipped") })
        assertTrue(result.logs.any { it.contains("manual fallback") })
    }

    @Test
    fun `empty workspace yields not available with fallback`() {
        val result = PreviewDetector.detect(
            exists = { false },
            readHead = { null },
            services = emptyList(),
            workspacePath = "/workspace/empty"
        )
        assertEquals(PreviewDetector.PreviewStatus.NOT_AVAILABLE, result.status)
        assertNull(result.target)
        assertFalse(result.autoOpen)
        assertTrue(result.logs.any { it.contains("workspace scanned") })
        assertTrue(result.logs.any { it.contains("preview not available") })
    }

    @Test
    fun `nextjs project is detected and prefers dev`() {
        val pkg = """{"dependencies":{"next":"14.0.0"},"scripts":{"dev":"next dev","build":"next build"}}"""
        val files = mapOf("package.json" to pkg, "pages/index.js" to "export default () => <div/>")
        val result = PreviewDetector.detect(
            exists = { it in files },
            readHead = { files[it] },
            services = emptyList(),
            workspacePath = "/workspace/next-app"
        )
        assertTrue(result.framework?.contains("Next.js") == true)
        assertEquals(PreviewDetector.PreviewStatus.NOT_AVAILABLE, result.status) // no file, but dev command exists
        assertTrue(result.logs.any { it.contains("Next.js") || it.contains("framework detected") })
    }

    @Test
    fun `scanning logs contain all required debug phrases`() {
        val files = mapOf("index.html" to "<html></html>")
        val result = PreviewDetector.detect(
            exists = { it in files },
            readHead = { files[it] },
            services = emptyList(),
            workspacePath = "/tmp/ws"
        )
        assertTrue(result.logs.any { it.contains("workspace scanned") })
        assertTrue(result.logs.any { it.contains("framework detected") })
        assertTrue(result.logs.any { it.contains("preview target found") })
        // For ready case should have preview opened
        assertTrue(result.logs.any { it.contains("preview ready") || it.contains("preview opened") })
    }
}
