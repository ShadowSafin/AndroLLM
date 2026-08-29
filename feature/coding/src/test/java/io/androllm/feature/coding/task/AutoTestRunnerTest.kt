package io.androllm.feature.coding.task

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AutoTestRunner: detects a project's test command and parses common output
 * shapes (npm / pytest / gradle / cargo). The actual command execution is
 * injected.
 */
class AutoTestRunnerTest {

    @Test
    fun `npm test is detected when package_json exists`() {
        val dir = newTempDir("npm")
        try {
            File(dir, "package.json").writeText("{}")
            val runner = AutoTestRunner { _, _ -> TestRunResult(0, "5 passing\n0 failing") }
            val result = kotlinx.coroutines.runBlocking { runner.run(dir) }
            assertNotNull(result)
            assertEquals("npm test", result!!.framework)
            assertEquals(5, result.passed)
            assertEquals(0, result.failed)
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun `pytest summary is parsed when pyproject exists`() {
        val dir = newTempDir("py")
        try {
            File(dir, "pyproject.toml").writeText("[project]\nname='x'")
            val runner = AutoTestRunner { _, _ ->
                TestRunResult(0, "===== 12 passed, 2 failed in 0.4s =====")
            }
            val result = kotlinx.coroutines.runBlocking { runner.run(dir) }
            assertNotNull(result)
            assertEquals("pytest", result!!.framework)
            assertEquals(12, result.passed)
            assertEquals(2, result.failed)
            assertTrue(result.isPass.not())
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun `cargo test summary is parsed when Cargo toml exists`() {
        val dir = newTempDir("cargo")
        try {
            File(dir, "Cargo.toml").writeText("[package]\nname=\"x\"")
            val runner = AutoTestRunner { _, _ ->
                TestRunResult(0, "test result: ok. 7 passed; 0 failed; 0 ignored")
            }
            val result = kotlinx.coroutines.runBlocking { runner.run(dir) }
            assertNotNull(result)
            assertEquals("cargo test", result!!.framework)
            assertEquals(7, result.passed)
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun `gradle test is detected when build gradle exists`() {
        val dir = newTempDir("gradle")
        try {
            File(dir, "build.gradle.kts").writeText("// gradle")
            val runner = AutoTestRunner { _, _ -> TestRunResult(0, "BUILD SUCCESSFUL") }
            val result = kotlinx.coroutines.runBlocking { runner.run(dir) }
            assertNotNull(result)
            assertEquals("gradle test", result!!.framework)
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun `no recognised project returns null`() {
        val dir = newTempDir("empty")
        try {
            val runner = AutoTestRunner { _, _ -> TestRunResult(0, "") }
            val result = kotlinx.coroutines.runBlocking { runner.run(dir) }
            assertNull(result)
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun `non-zero exit with no summary counts as one error`() {
        val dir = newTempDir("err")
        try {
            File(dir, "package.json").writeText("{}")
            val runner = AutoTestRunner { _, _ -> TestRunResult(1, "module crashed") }
            val result = kotlinx.coroutines.runBlocking { runner.run(dir) }
            assertNotNull(result)
            assertEquals(1, result!!.error)
        } finally { dir.deleteRecursively() }
    }

    private fun newTempDir(prefix: String): File =
        java.nio.file.Files.createTempDirectory(prefix).toFile()
}
