package io.androllm.feature.coding.task

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Auto-recovery strategy: classifies failed commands and proposes safe fixes. */
class CommandRecoveryTest {

    @Test
    fun `npm peer dep conflict gets --legacy-peer-deps`() {
        val plan = CommandRecovery.suggest(
            command = "npm install",
            outputTail = "npm ERR! ERESOLVE could not resolve\npeer dep react@18 not satisfied"
        )
        assertNotNull(plan)
        assertEquals("npm_peer_deps", plan!!.category)
        assertTrue(plan.command.contains("--legacy-peer-deps"))
    }

    @Test
    fun `pnpm peer dep conflict gets --no-strict-peer-dependencies`() {
        val plan = CommandRecovery.suggest(
            command = "pnpm install",
            outputTail = "ERR_PNPM_PEER_DEP_ISSUES unresolved peer"
        )
        assertNotNull(plan)
        assertEquals("pnpm_peer_deps", plan!!.category)
        assertTrue(plan.command.contains("--no-strict-peer-dependencies"))
    }

    @Test
    fun `npm missing module gets node_modules wipe + reinstall`() {
        val plan = CommandRecovery.suggest(
            command = "npm run build",
            outputTail = "Error: Cannot find module 'lodash'"
        )
        assertNotNull(plan)
        assertEquals("reinstall", plan!!.category)
        assertTrue(plan.command.contains("rm -rf node_modules"))
        assertTrue(plan.command.contains("npm run build"))
    }

    @Test
    fun `vite port conflict retries on alternate port`() {
        val plan = CommandRecovery.suggest(
            command = "npx vite --port 5173",
            outputTail = "Error: EADDRINUSE: address already in use :::5173"
        )
        assertNotNull(plan)
        assertEquals("vite_port", plan!!.category)
        assertTrue(plan.command.contains("--port 5180"))
    }

    @Test
    fun `python missing module installs it via pip`() {
        val plan = CommandRecovery.suggest(
            command = "python main.py",
            outputTail = "ModuleNotFoundError: No module named 'flask'"
        )
        assertNotNull(plan)
        assertEquals("pip_install", plan!!.category)
        assertEquals("pip install flask", plan.command)
    }

    @Test
    fun `gradle daemon stuck restarts the daemon first`() {
        val plan = CommandRecovery.suggest(
            command = "./gradlew test",
            outputTail = "Could not connect to the daemon. Daemon has stopped."
        )
        assertNotNull(plan)
        assertEquals("gradle_daemon", plan!!.category)
        assertTrue(plan.command.contains("./gradlew --stop"))
    }

    @Test
    fun `typescript build cache stale clears it`() {
        val plan = CommandRecovery.suggest(
            command = "tsc --noEmit",
            outputTail = "error TS2307: Cannot find module 'react'.\nerror TS2307..."
        )
        assertNotNull(plan)
        assertEquals("ts_build", plan!!.category)
        assertTrue(plan.command.contains("rm -rf node_modules/.cache"))
    }

    @Test
    fun `unrelated failure returns null`() {
        val plan = CommandRecovery.suggest(
            command = "echo hello",
            outputTail = "Permission denied (publickey)."
        )
        assertNull(plan)
    }

    @Test
    fun `non-install command does not match npm rules`() {
        val plan = CommandRecovery.suggest(
            command = "echo hi",
            outputTail = "ERESOLVE peer dep something"
        )
        assertNull("non-install command should not match", plan)
    }
}
