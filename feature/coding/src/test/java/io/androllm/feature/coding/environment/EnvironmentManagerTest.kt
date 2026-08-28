package io.androllm.feature.coding.environment

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Installer that fails [failTimes] per addon before succeeding. */
class FlakyInstaller(private val failTimes: Int) : AddonInstallerBackend {
    val attempts = mutableMapOf<String, Int>()
    override suspend fun install(
        pkg: RuntimePackage,
        installDir: File,
        onProgress: (InstallProgress) -> Unit
    ): Boolean {
        val n = (attempts[pkg.id] ?: 0) + 1
        attempts[pkg.id] = n
        return if (n <= failTimes) {
            onProgress(InstallProgress(pkg.id, InstallStatus.FAILED, 0, message = "boom"))
            false
        } else {
            installDir.mkdirs()
            File(installDir, SimulatedAddonInstaller.MARKER).writeText(pkg.id)
            onProgress(InstallProgress(pkg.id, InstallStatus.INSTALLED, 100, message = "ok"))
            true
        }
    }
}

/** Addon install lifecycle: chains, progress, retry, uninstall, PATH wiring. */
class EnvironmentManagerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun manager(installer: AddonInstallerBackend = SimulatedAddonInstaller()): EnvironmentManager {
        val root = tmp.newFolder("env")
        return EnvironmentManager(envRoot = { root }, installer = installer)
    }

    @Test
    fun `initially nothing is installed`() {
        val mgr = manager()
        assertTrue(mgr.installedAddons().isEmpty())
        assertFalse(mgr.isInstalled("nodejs"))
        assertEquals(InstallStatus.NOT_INSTALLED, mgr.statusOf("nodejs"))
    }

    @Test
    fun `install nodejs marks it installed and writes marker`() = runBlocking {
        val mgr = manager()
        val ok = mgr.install("nodejs")
        assertTrue(ok)
        assertTrue(mgr.isInstalled("nodejs"))
        assertEquals(InstallStatus.INSTALLED, mgr.statusOf("nodejs"))
    }

    @Test
    fun `install pnpm also installs nodejs dependency`() = runBlocking {
        val mgr = manager()
        val ok = mgr.install("pnpm")
        assertTrue(ok)
        assertTrue("nodejs must be installed as a dependency", mgr.isInstalled("nodejs"))
        assertTrue(mgr.isInstalled("pnpm"))
    }

    @Test
    fun `path entries expose bin dirs of installed addons`() = runBlocking {
        val mgr = manager()
        mgr.install("nodejs")
        val entries = mgr.pathEntries()
        assertTrue(entries.any { it.endsWith("nodejs${File.separator}bin") })
    }

    @Test
    fun `install publishes progress ending in installed`() = runBlocking {
        val mgr = manager()
        mgr.install("git")
        val progress = mgr.progress.value["git"]
        assertEquals(InstallStatus.INSTALLED, progress?.status)
        assertEquals(100, progress?.percent)
    }

    @Test
    fun `retry after failure eventually installs`() = runBlocking {
        val flaky = FlakyInstaller(failTimes = 1)
        val mgr = manager(flaky)
        val first = mgr.install("python")
        assertFalse("first attempt fails", first)
        assertEquals(InstallStatus.FAILED, mgr.statusOf("python"))
        val second = mgr.retryInstall("python")
        assertTrue("retry succeeds", second)
        assertTrue(mgr.isInstalled("python"))
        assertEquals(2, flaky.attempts["python"])
    }

    @Test
    fun `uninstall removes addon`() = runBlocking {
        val mgr = manager()
        mgr.install("go")
        assertTrue(mgr.isInstalled("go"))
        mgr.uninstall("go")
        assertFalse(mgr.isInstalled("go"))
    }

    @Test
    fun `installed state survives a manager reload from disk`() = runBlocking {
        val root = tmp.newFolder("env2")
        val first = EnvironmentManager(envRoot = { root })
        first.install("rust")
        // A fresh manager over the same root must see the persisted marker.
        val second = EnvironmentManager(envRoot = { root })
        assertTrue(second.isInstalled("rust"))
    }
}
