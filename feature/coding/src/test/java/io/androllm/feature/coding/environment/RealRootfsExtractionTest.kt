package io.androllm.feature.coding.environment

import io.androllm.feature.coding.environment.proot.ProotFiles
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Reproduces on-device provisioning against the REAL Debian rootfs tarball so
 * extraction bugs surface on the JVM (symlink handling, merged-usr, xz, entry
 * types, etc.). The tarball is large (~90 MB) and lives out of git, so these
 * tests are SKIPPED when it is not present locally (see tools/debian-rootfs).
 */
class RealRootfsExtractionTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun realTarball(): File? {
        val candidates = listOf(
            File("../../tools/debian-rootfs/rootfs.tar.xz"),
            File("../tools/debian-rootfs/rootfs.tar.xz"),
            File("tools/debian-rootfs/rootfs.tar.xz")
        )
        return candidates.firstOrNull { it.exists() }
    }

    /** Existence check that does NOT follow symlinks (mirrors production). */
    private fun existsNoFollow(f: File): Boolean =
        Files.exists(f.toPath(), LinkOption.NOFOLLOW_LINKS)

    @Test
    fun `real debian tarball extracts completely`() = runBlocking {
        val found = realTarball()
        assumeTrue("real Debian tarball not present locally; skipping", found != null)
        val tarball: File = found!!

        val root = tmp.newFolder("real")
        val nativeLib = File(root, "lib").apply { mkdirs() }
        File(nativeLib, "libproot.so").writeText("x")
        File(nativeLib, "libproot-loader.so").writeText("x")
        val files = ProotFiles(nativeLib, File(root, "rootfs"), File(root, "tmp"))

        val provider = object : RootfsTarballProvider {
            override suspend fun ensureTarball(onProgress: (Long, Long) -> Unit): File = tarball
            override fun cachePath(): File = tarball
        }
        val mgr = LinuxBaseManager(files, provider)
        val ok = mgr.provision()

        val rootfs = File(root, "rootfs")
        val fileCount = rootfs.walkTopDown().count { it.isFile }
        println("EXTRACT ok=$ok phase=${mgr.status.value.phase} msg='${mgr.status.value.message}' files=$fileCount")
        println("usr/bin/dpkg exists=${File(rootfs, "usr/bin/dpkg").exists()} exec=${File(rootfs, "usr/bin/dpkg").canExecute()}")
        println("usr/bin/sh exists(nofollow)=${existsNoFollow(File(rootfs, "usr/bin/sh"))}")
        println("etc/apt/sources.list exists=${File(rootfs, "etc/apt/sources.list").exists()}")
        println("etc/resolv.conf isFile=${File(rootfs, "etc/resolv.conf").isFile}")

        assertTrue("provision failed: ${mgr.status.value.message}", ok)
        assertTrue(mgr.isInstalled())
        assertTrue("expected a populated rootfs", fileCount > 5000)
        assertTrue(File(rootfs, "usr/bin/dpkg").exists())
        assertTrue("usr/bin/sh should exist (as file or symlink)", existsNoFollow(File(rootfs, "usr/bin/sh")))
        // resolv.conf must be a REAL file (the image ships a dead systemd symlink)
        assertTrue("resolv.conf must be replaced with a real file", File(rootfs, "etc/resolv.conf").isFile)
        assertTrue(File(rootfs, "etc/resolv.conf").readText().contains("nameserver"))
    }
}
