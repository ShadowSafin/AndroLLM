package io.androllm.feature.coding.environment

import io.androllm.feature.coding.environment.proot.ProotFiles
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Real rootfs provisioning: tar(.gz/.xz) extraction, symlinks, exec bits, DNS, markers. */
class LinuxBaseManagerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** Builds a tiny stand-in for the Debian rootfs tarball. */
    private fun buildRootfsTar(): ByteArray {
        val out = ByteArrayOutputStream()
        TarArchiveOutputStream(out).use { tar ->
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)

            fun addDir(name: String) {
                val e = TarArchiveEntry(name)
                e.mode = 0b111_101_101
                tar.putArchiveEntry(e); tar.closeArchiveEntry()
            }
            fun addFile(name: String, content: String, exec: Boolean = false) {
                val bytes = content.toByteArray()
                val e = TarArchiveEntry(name)
                e.size = bytes.size.toLong()
                e.mode = if (exec) 0b111_101_101 else 0b110_100_100
                tar.putArchiveEntry(e); tar.write(bytes); tar.closeArchiveEntry()
            }
            fun addSymlink(name: String, target: String) {
                val e = TarArchiveEntry(name, TarArchiveEntry.LF_SYMLINK)
                e.linkName = target
                tar.putArchiveEntry(e); tar.closeArchiveEntry()
            }

            addDir("./")
            addDir("bin/")
            addFile("bin/busybox", "#!/bin/sh\necho busybox\n", exec = true)
            addSymlink("bin/sh", "busybox")
            addDir("etc/")
            addFile("etc/os-release", "NAME=Debian\n")
            addDir("usr/bin/")
            addFile("usr/bin/dpkg", "#!/bin/sh\necho dpkg\n", exec = true)
            addSymlink("usr/bin/sh", "../../bin/busybox")
            addSymlink("usr/bin/env", "../../bin/busybox")
        }
        return out.toByteArray()
    }

    private fun gzip(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GzipCompressorOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }

    private fun xz(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        XZCompressorOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }

    private fun files(root: File): ProotFiles {
        val nativeLib = File(root, "lib").apply { mkdirs() }
        // Fake the bundled proot binaries so binariesPresent() is satisfied.
        File(nativeLib, "libproot.so").writeText("fake")
        File(nativeLib, "libproot-loader.so").writeText("fake")
        return ProotFiles(
            nativeLibDir = nativeLib,
            rootfsDir = File(root, "rootfs"),
            tmpDir = File(root, "tmp")
        )
    }

    /** Test provider: the tarball bytes are materialized as a cached file. */
    private fun provider(root: File, tarballBytes: ByteArray): RootfsTarballProvider {
        val tarball = File(root, "rootfs-download.tar.xz")
        tarball.writeBytes(tarballBytes)
        return object : RootfsTarballProvider {
            override suspend fun ensureTarball(onProgress: (Long, Long) -> Unit): File {
                onProgress(tarball.length(), tarball.length())
                return tarball
            }
            override fun cachePath(): File = tarball
        }
    }

    private fun manager(root: File, tarball: ByteArray = gzip(buildRootfsTar())): LinuxBaseManager =
        LinuxBaseManager(
            files = files(root),
            tarballs = provider(root, tarball),
            expectedRootfsBytes = 4096
        )

    @Test
    fun `initially not installed`() {
        val mgr = manager(tmp.newFolder("a"))
        assertFalse(mgr.isInstalled())
        assertEquals(LinuxBasePhase.IDLE, mgr.currentStatus().phase)
    }

    @Test
    fun `provision extracts rootfs with files dirs symlinks and exec bits`() = runBlocking {
        val root = tmp.newFolder("b")
        val mgr = manager(root)
        val ok = mgr.provision()
        assertTrue(ok)
        assertTrue(mgr.isInstalled())

        val rootfs = File(root, "rootfs")
        assertTrue(File(rootfs, "bin/busybox").isFile)
        assertTrue("busybox must keep its exec bit", File(rootfs, "bin/busybox").canExecute())
        assertTrue(File(rootfs, "etc/os-release").isFile)
        // bin/sh symlink (or its fallback copy) must exist and resolve to busybox content
        val sh = File(rootfs, "bin/sh")
        assertTrue(sh.exists())
        assertEquals(LinuxBasePhase.READY, mgr.status.value.phase)
    }

    @Test
    fun `provision writes dns resolv conf`() = runBlocking {
        val root = tmp.newFolder("c")
        val mgr = manager(root)
        mgr.provision()
        val resolv = File(root, "rootfs/etc/resolv.conf")
        assertTrue(resolv.exists())
        assertTrue(resolv.readText().contains("nameserver"))
    }

    @Test
    fun `provision writes apt proot config and policy-rc`() = runBlocking {
        val root = tmp.newFolder("c2")
        val mgr = manager(root)
        mgr.provision()
        val rootfs = File(root, "rootfs")
        val aptConf = File(rootfs, "etc/apt/apt.conf.d/99androllm-proot")
        assertTrue(aptConf.exists())
        assertTrue(aptConf.readText().contains("APT::Sandbox::User"))
        val policyRc = File(rootfs, "usr/sbin/policy-rc.d")
        assertTrue(policyRc.exists())
        assertTrue(policyRc.readText().contains("exit 101"))
    }

    @Test
    fun `provision is idempotent once installed`() = runBlocking {
        val root = tmp.newFolder("d")
        val mgr = manager(root)
        assertTrue(mgr.provision())
        assertTrue(mgr.provision())
        assertEquals("Already installed", mgr.status.value.message)
    }

    @Test
    fun `failed extraction reports failure and cleans up`() = runBlocking {
        val root = tmp.newFolder("e")
        val mgr = manager(root, tarball = byteArrayOf(1, 2, 3)) // not a tar
        val ok = mgr.provision()
        assertFalse(ok)
        assertEquals(LinuxBasePhase.FAILED, mgr.status.value.phase)
        assertFalse(mgr.isInstalled())
    }

    @Test
    fun `remove deletes rootfs marker and cached tarball`() = runBlocking {
        val root = tmp.newFolder("f")
        val mgr = manager(root)
        mgr.provision()
        assertTrue(mgr.isInstalled())
        val tarball = File(root, "rootfs-download.tar.xz")
        assertTrue(tarball.exists())
        mgr.remove()
        assertFalse(mgr.isInstalled())
        assertFalse(File(root, "rootfs").exists())
        assertFalse(tarball.exists())
        assertEquals(LinuxBasePhase.IDLE, mgr.status.value.phase)
    }

    @Test
    fun `installed state survives reload via marker`() = runBlocking {
        val root = tmp.newFolder("g")
        manager(root).provision()
        val reloaded = manager(root)
        assertTrue(reloaded.isInstalled())
        assertEquals(LinuxBasePhase.READY, reloaded.currentStatus().phase)
    }

    @Test
    fun `old alpine marker is not treated as installed`() = runBlocking {
        val root = tmp.newFolder("g2")
        val mgr = manager(root)
        mgr.provision()
        // Simulate a leftover marker from the pre-Debian Alpine base.
        File(root, ".base-ready").writeText("alpine-minirootfs\nextracted-files=1\n")
        assertFalse(mgr.isInstalled())
    }

    @Test
    fun `missing proot binaries fail provisioning with a clear message`() = runBlocking {
        val root = tmp.newFolder("h")
        val f = files(root)
        f.prootBinary.delete() // simulate a broken build
        val mgr = LinuxBaseManager(f, provider(root, gzip(buildRootfsTar())))
        assertFalse(mgr.provision())
        assertTrue(mgr.status.value.message.contains("proot"))
    }

    @Test
    fun `plain uncompressed tar also extracts`() = runBlocking {
        val root = tmp.newFolder("i")
        val mgr = manager(root, tarball = buildRootfsTar())
        assertTrue(mgr.provision())
        assertTrue(File(root, "rootfs/bin/sh").exists())
        assertTrue(File(root, "rootfs/bin/busybox").exists())
    }

    @Test
    fun `xz compressed tarball extracts (production format)`() = runBlocking {
        val root = tmp.newFolder("j")
        val mgr = manager(root, tarball = xz(buildRootfsTar()))
        assertTrue(mgr.provision())
        assertTrue(mgr.isInstalled())
        assertTrue(File(root, "rootfs/usr/bin/dpkg").exists())
    }
}
