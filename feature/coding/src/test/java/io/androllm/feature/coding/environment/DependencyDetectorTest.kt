package io.androllm.feature.coding.environment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Missing-runtime detection that drives the marketplace auto-install prompt. */
class DependencyDetectorTest {

    private val none = emptySet<String>()

    @Test
    fun `detects missing nodejs for npm`() {
        val missing = DependencyDetector.detectMissing("npm run build", none)
        assertEquals("nodejs", missing?.addonId)
        assertEquals("npm", missing?.command)
    }

    @Test
    fun `no missing when nodejs installed for npm`() {
        assertNull(DependencyDetector.detectMissing("npm run build", setOf("nodejs")))
    }

    @Test
    fun `pnpm requires nodejs first in the chain`() {
        // With nothing installed, pnpm's first unmet requirement is nodejs.
        val missing = DependencyDetector.detectMissing("pnpm install", none)
        assertEquals("nodejs", missing?.addonId)
    }

    @Test
    fun `pnpm with nodejs installed still needs pnpm`() {
        val missing = DependencyDetector.detectMissing("pnpm install", setOf("nodejs"))
        assertEquals("pnpm", missing?.addonId)
    }

    @Test
    fun `yarn needs nodejs and yarn`() {
        assertEquals("nodejs", DependencyDetector.detectMissing("yarn install", none)?.addonId)
        assertEquals("yarn", DependencyDetector.detectMissing("yarn install", setOf("nodejs"))?.addonId)
    }

    @Test
    fun `detects python for python command`() {
        assertEquals("python", DependencyDetector.detectMissing("python script.py", none)?.addonId)
        assertEquals("python", DependencyDetector.detectMissing("python3 -m pytest", none)?.addonId)
    }

    @Test
    fun `detects git for git command`() {
        assertEquals("git", DependencyDetector.detectMissing("git status", none)?.addonId)
    }

    @Test
    fun `detects java for gradlew`() {
        assertEquals("java", DependencyDetector.detectMissing("./gradlew assembleDebug", none)?.addonId)
        assertEquals("java", DependencyDetector.detectMissing("gradle build", none)?.addonId)
    }

    @Test
    fun `detects go and rust`() {
        assertEquals("go", DependencyDetector.detectMissing("go build ./...", none)?.addonId)
        assertEquals("rust", DependencyDetector.detectMissing("cargo build", none)?.addonId)
    }

    @Test
    fun `base shell commands are never missing`() {
        assertNull(DependencyDetector.detectMissing("ls -la", none))
        assertNull(DependencyDetector.detectMissing("cat file.txt", none))
        assertNull(DependencyDetector.detectMissing("grep foo .", none))
        assertNull(DependencyDetector.detectMissing("mkdir -p out", none))
    }

    @Test
    fun `unknown command returns no missing`() {
        assertNull(DependencyDetector.detectMissing("some-unknown-tool --flag", none))
    }

    @Test
    fun `skips env assignments and sudo when finding executable`() {
        assertEquals("nodejs", DependencyDetector.detectMissing("FOO=1 npm start", none)?.addonId)
        assertEquals("git", DependencyDetector.detectMissing("sudo git pull", none)?.addonId)
    }
}
