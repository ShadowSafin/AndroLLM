package io.androllm.feature.coding.environment

import io.androllm.feature.coding.environment.proot.ProotFiles
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Real addon installs via apt: mapping, gating on the base, success/failure. */
class ApkAddonInstallerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var root: File
    private lateinit var base: LinuxBaseManager
    private lateinit var backend: FakeShellBackend
    private lateinit var installer: ApkAddonInstaller

    private fun unusedProvider(): RootfsTarballProvider = object : RootfsTarballProvider {
        override suspend fun ensureTarball(onProgress: (Long, Long) -> Unit): File = error("not used")
        override fun cachePath(): File = error("not used")
    }

    private fun provisionedBase(): LinuxBaseManager {
        val nativeLib = File(root, "lib").apply { mkdirs() }
        File(nativeLib, "libproot.so").writeText("x")
        File(nativeLib, "libproot-loader.so").writeText("x")
        val rootfs = File(root, "rootfs")
        File(rootfs, "bin").mkdirs()
        File(rootfs, "bin/sh").writeText("#!/bin/sh\n")
        File(rootfs, "usr/bin").mkdirs()
        File(rootfs, "usr/bin/dpkg").writeText("#!/bin/sh\n")
        File(rootfs, "usr/bin/sh").writeText("#!/bin/sh\n")
        // Marker lives next to the rootfs (see LinuxBaseManager.marker()).
        File(root, LinuxBaseManager.MARKER).writeText("${LinuxBaseManager.MARKER_FLAVOR}\n")
        val files = ProotFiles(nativeLib, rootfs, File(root, "tmp"))
        return LinuxBaseManager(files, unusedProvider())
    }

    private fun unprovisionedBase(): LinuxBaseManager {
        val nativeLib = File(root, "lib2").apply { mkdirs() }
        val files = ProotFiles(nativeLib, File(root, "rootfs-none"), File(root, "tmp"))
        return LinuxBaseManager(files, unusedProvider())
    }

    private fun setUp(baseInstalled: Boolean, responder: (String, File) -> CommandResult? = { _, _ -> null }) {
        root = tmp.newFolder("env")
        base = if (baseInstalled) provisionedBase() else unprovisionedBase()
        backend = FakeShellBackend(responder)
        installer = ApkAddonInstaller(base, backend, File(root, "scratch"))
        assertTrue("precondition", baseInstalled == base.isInstalled())
    }

    @Test
    fun `every marketplace addon has a package mapping`() {
        for (pkg in MarketplaceCatalog.packages) {
            assertNotNull("missing mapping for ${pkg.id}", ApkAddonInstaller.APK_PACKAGES[pkg.id])
        }
    }

    @Test
    fun `nodejs maps to nodejs and npm packages`() {
        assertEquals(listOf("nodejs", "npm"), ApkAddonInstaller.APK_PACKAGES["nodejs"])
    }

    @Test
    fun `install fails fast when base not provisioned`() = runBlocking {
        setUp(baseInstalled = false)
        val pkg = MarketplaceCatalog.find("nodejs")!!
        var lastMsg = ""
        val ok = installer.install(pkg, File(root, "addons/nodejs")) { lastMsg = it.message }
        assertFalse(ok)
        assertTrue(lastMsg.contains("Linux base"))
        assertTrue("no shell command should run", backend.invocations.isEmpty())
    }

    @Test
    fun `install runs apt-get install and records marker on success`() = runBlocking {
        setUp(baseInstalled = true) { cmd, dir ->
            CommandResult(cmd, 0, stdout = "OK: installed nodejs npm", workingDir = dir.path)
        }
        val pkg = MarketplaceCatalog.find("nodejs")!!
        val addonDir = File(root, "addons/nodejs")
        val ok = installer.install(pkg, addonDir) { }
        assertTrue(ok)
        assertEquals(1, backend.invocations.size)
        val cmd = backend.invocations[0].command
        assertTrue("must run apt-get update", cmd.contains("apt-get update"))
        assertTrue("must run apt-get install", cmd.contains("apt-get install"))
        assertTrue(cmd.contains("nodejs"))
        assertTrue(cmd.contains("npm"))
        assertTrue("marker written for state parity", File(addonDir, SimulatedAddonInstaller.MARKER).exists())
    }

    @Test
    fun `install reports apt failure with output tail`() = runBlocking {
        setUp(baseInstalled = true) { cmd, dir ->
            CommandResult(cmd, 4, stdout = "", stderr = "E: Unable to locate package", workingDir = dir.path)
        }
        val pkg = MarketplaceCatalog.find("git")!!
        var lastMsg = ""
        val ok = installer.install(pkg, File(root, "addons/git")) { lastMsg = it.message }
        assertFalse(ok)
        assertTrue(lastMsg.contains("apt failed"))
        assertTrue(lastMsg.contains("Unable to locate package"))
    }

    @Test
    fun `unknown addon id fails cleanly`() = runBlocking {
        setUp(baseInstalled = true)
        val unknown = RuntimePackage(
            id = "does-not-exist", name = "?", description = "", version = "", sizeBytes = 0,
            kind = PackageKind.UTILITY
        )
        var lastMsg = ""
        val ok = installer.install(unknown, File(root, "addons/x")) { lastMsg = it.message }
        assertFalse(ok)
        assertTrue(lastMsg.contains("No Debian package mapping"))
    }

    @Test
    fun `delegating installer routes to apt when base ready else simulated`() = runBlocking {
        setUp(baseInstalled = true) { cmd, dir ->
            CommandResult(cmd, 0, stdout = "ok", workingDir = dir.path)
        }
        val delegating = DelegatingAddonInstaller(base, installer, SimulatedAddonInstaller())
        val pkg = MarketplaceCatalog.find("python")!!
        val dir = File(root, "addons/python")
        assertTrue(delegating.install(pkg, dir) { })
        assertTrue("went through apt shell", backend.invocations.isNotEmpty())
    }

    @Test
    fun `delegating installer auto-provisions the base then installs via apt`() = runBlocking {
        // Start with an UNprovisioned but provisionable base (real tarball provider).
        root = tmp.newFolder("auto")
        val nativeLib = File(root, "lib").apply { mkdirs() }
        File(nativeLib, "libproot.so").writeText("x")
        File(nativeLib, "libproot-loader.so").writeText("x")
        val files = ProotFiles(nativeLib, File(root, "rootfs"), File(root, "tmp"))
        val tarball = File(root, "rootfs.tar.gz").apply { writeBytes(miniRootfsTarGz()) }
        val provider = object : RootfsTarballProvider {
            override suspend fun ensureTarball(onProgress: (Long, Long) -> Unit): File = tarball
            override fun cachePath(): File = tarball
        }
        base = LinuxBaseManager(files, provider)
        assertFalse("precondition: not yet provisioned", base.isInstalled())

        backend = FakeShellBackend { cmd, dir -> CommandResult(cmd, 0, stdout = "ok", workingDir = dir.path) }
        val apkInstaller = ApkAddonInstaller(base, backend, File(root, "scratch"))
        val delegating = DelegatingAddonInstaller(base, apkInstaller, SimulatedAddonInstaller())

        val pkg = MarketplaceCatalog.find("git")!!
        val ok = delegating.install(pkg, File(root, "addons/git")) { }
        assertTrue(ok)
        assertTrue("base was auto-provisioned", base.isInstalled())
        assertTrue("then apt ran", backend.invocations.any { it.command.contains("apt-get install") })
    }

    /** Tiny gzip'd tar with bin/sh + usr/bin/dpkg so LinuxBaseManager.provision() succeeds. */
    private fun miniRootfsTarGz(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        org.apache.commons.compress.archivers.tar.TarArchiveOutputStream(
            org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream(out)
        ).use { tar ->
            fun dir(name: String) {
                val d = org.apache.commons.compress.archivers.tar.TarArchiveEntry(name)
                d.mode = 0b111_101_101
                tar.putArchiveEntry(d); tar.closeArchiveEntry()
            }
            fun file(name: String, content: String) {
                val bytes = content.toByteArray()
                val e = org.apache.commons.compress.archivers.tar.TarArchiveEntry(name)
                e.size = bytes.size.toLong(); e.mode = 0b111_101_101
                tar.putArchiveEntry(e); tar.write(bytes); tar.closeArchiveEntry()
            }
            dir("bin/")
            file("bin/busybox", "#!/bin/sh\necho busybox\n")
            file("bin/sh", "#!/bin/sh\n")
            dir("usr/bin/")
            file("usr/bin/dpkg", "#!/bin/sh\necho dpkg\n")
            file("usr/bin/sh", "#!/bin/sh\n")
        }
        return out.toByteArray()
    }
}
