package io.androllm.feature.coding.environment

import io.androllm.feature.coding.environment.proot.ProotFiles
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import timber.log.Timber

/** Phases of provisioning the real Linux base environment. */
enum class LinuxBasePhase {
    IDLE,
    DOWNLOADING,
    VERIFYING,
    EXTRACTING,
    READY,
    FAILED
}

/** Snapshot of the base environment's provisioning state. */
data class LinuxBaseStatus(
    val phase: LinuxBasePhase = LinuxBasePhase.IDLE,
    val percent: Int = 0,
    val installed: Boolean = false,
    val message: String = ""
)

/**
 * Supplies the Debian rootfs tarball that provisioning extracts. Production
 * downloads it on first use and caches it (see
 * [io.androllm.feature.coding.environment.proot.DebianRootfsDownloader]);
 * tests point at a local file.
 */
interface RootfsTarballProvider {
    /**
     * Returns a readable tarball file, downloading it first if needed.
     * [onProgress] receives (bytesDone, bytesTotal) — bytesTotal may be -1
     * when the size is unknown.
     */
    suspend fun ensureTarball(onProgress: (Long, Long) -> Unit): File

    /** Where the tarball is cached on disk (deleted by [LinuxBaseManager.remove]). */
    fun cachePath(): File
}

/**
 * Provisions the REAL Linux base environment the coding CLI runs in: a genuine
 * Debian userland (apt + dpkg + glibc + coreutils) extracted from an official
 * Debian rootfs tarball, executed through proot. Once provisioned, marketplace
 * addons are installed with real `apt-get install` inside this userland, so
 * commands like `npm run dev`, `python`, `git`, `gcc` actually execute.
 *
 * Why Debian (instead of the previous Alpine base): Alpine's musl resolver and
 * apk fetch path hit Android/proot permission walls on modern devices even
 * though raw sockets work; Debian's glibc userland is the battle-tested proot
 * combination (the same one proot-distro ships) and `apt` works reliably.
 *
 * Why this works on non-rooted Android: the rootfs lives in app storage, which
 * Android mounts noexec — but proot's loader maps guest ELFs into memory
 * instead of exec'ing them, so guest binaries run anyway. proot itself is
 * exec'd from the app's native library dir (the one executable location).
 *
 * State survives process death via a `.base-ready` marker next to the rootfs.
 */
class LinuxBaseManager(
    private val files: ProotFiles,
    private val tarballs: RootfsTarballProvider,
    private val expectedRootfsBytes: Long = DEFAULT_EXPECTED_ROOTFS_BYTES
) {
    private val _status = MutableStateFlow(LinuxBaseStatus())
    val status: StateFlow<LinuxBaseStatus> = _status.asStateFlow()

    private fun marker(): File = File(files.rootfsDir.parentFile ?: files.rootfsDir, MARKER)

    /**
     * True when [f] exists as a file OR as a symlink (broken or not). Unlike
     * [File.exists], this does NOT follow symlinks — critical because rootfs
     * symlinks such as `bin/sh -> dash` are absolute and only resolve *inside*
     * proot, never on the Android host. Following them on the host would
     * report a perfectly-extracted rootfs as missing.
     */
    private fun existsNoFollow(f: File): Boolean =
        java.nio.file.Files.exists(f.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)

    /** True once the base environment has been provisioned and looks intact. */
    fun isInstalled(): Boolean {
        val m = marker()
        if (!m.exists() || !m.readText().startsWith(MARKER_FLAVOR)) return false
        // Debian merged-usr: check usr/bin directly (bin is a symlink to it,
        // and usr/bin/sh -> dash is itself a symlink, so do not follow it).
        return existsNoFollow(File(files.rootfsDir, "usr/bin/sh")) &&
            File(files.rootfsDir, "usr/bin/dpkg").exists() &&
            files.binariesPresent()
    }

    /** Current status, reconciled with the on-disk marker. */
    fun currentStatus(): LinuxBaseStatus {
        val installed = isInstalled()
        val s = _status.value
        return if (installed && s.phase != LinuxBasePhase.READY) {
            LinuxBaseStatus(LinuxBasePhase.READY, 100, installed = true, message = "Linux base (Debian) ready")
        } else {
            s.copy(installed = installed)
        }
    }

    /**
     * Provisions the base: downloads (first use) + extracts the rootfs,
     * configures DNS/apt, writes the marker. No-op (returns true) when already
     * installed. Failed provisions clean up partial state and can be retried.
     */
    suspend fun provision(): Boolean {
        if (isInstalled()) {
            _status.value = LinuxBaseStatus(LinuxBasePhase.READY, 100, installed = true, message = "Already installed")
            return true
        }
        if (!files.binariesPresent()) {
            Timber.w(
                "proot binaries missing: proot=%s loader=%s (nativeLibDir=%s)",
                files.prootBinary.exists(), files.loader.exists(), files.nativeLibDir
            )
            _status.value = LinuxBaseStatus(
                LinuxBasePhase.FAILED, 0, installed = false,
                message = "proot binaries are missing from this build — fully uninstall, then reinstall the app (Apply Changes does not update native libraries)."
            )
            return false
        }

        val rootfs = files.rootfsDir
        Timber.i("Provisioning Debian base into %s", rootfs)
        return try {
            _status.value = LinuxBaseStatus(LinuxBasePhase.DOWNLOADING, 2, message = "Fetching Debian base image...")
            val tarball = tarballs.ensureTarball { done, total ->
                val pct = if (total > 0) (2 + (done * 28) / total).toInt().coerceIn(2, 30) else 15
                _status.value = LinuxBaseStatus(
                    LinuxBasePhase.DOWNLOADING, pct,
                    message = if (total > 0) "Downloading Debian base... ${done / 1_000_000}/${total / 1_000_000} MB"
                    else "Downloading Debian base... ${done / 1_000_000} MB"
                )
            }

            _status.value = LinuxBaseStatus(LinuxBasePhase.EXTRACTING, 31, message = "Extracting Debian base...")
            val extracted = withContext(Dispatchers.IO) {
                if (rootfs.exists()) rootfs.deleteRecursively()
                rootfs.mkdirs()
                tarball.inputStream().buffered().use { input ->
                    extractRootfs(input, rootfs) { bytes ->
                        val pct = (31 + (bytes * 65) / expectedRootfsBytes).toInt().coerceIn(31, 96)
                        _status.value = LinuxBaseStatus(LinuxBasePhase.EXTRACTING, pct, message = "Extracting Debian base...")
                    }
                }
            }
            Timber.i("Extracted %d rootfs entries", extracted)
            withContext(Dispatchers.IO) { configureRootfs(rootfs) }
            // Guard against truncated/corrupt tarballs that "extract" to nothing.
            if (!existsNoFollow(File(rootfs, "usr/bin/sh")) || !File(rootfs, "usr/bin/dpkg").exists()) {
                throw IllegalStateException("Rootfs extraction produced no usable userland (usr/bin/sh or usr/bin/dpkg missing)")
            }
            marker().writeText("$MARKER_FLAVOR\nextracted-files=$extracted\n")
            _status.value = LinuxBaseStatus(LinuxBasePhase.READY, 100, installed = true, message = "Linux base (Debian) ready")
            Timber.i("Debian base provisioned OK (%d entries)", extracted)
            true
        } catch (t: Throwable) {
            Timber.e(t, "Linux base provisioning failed")
            runCatching { if (rootfs.exists()) rootfs.deleteRecursively() }
            _status.value = LinuxBaseStatus(
                LinuxBasePhase.FAILED, 0, installed = false,
                message = "Base provisioning failed: ${t.message ?: "unknown error"} — tap to retry"
            )
            false
        }
    }

    /** Removes the base environment (does not touch workspaces or addons state). */
    suspend fun remove() {
        withContext(Dispatchers.IO) {
            runCatching { marker().delete() }
            runCatching { files.rootfsDir.deleteRecursively() }
            runCatching { tarballs.cachePath().delete() }
        }
        _status.value = LinuxBaseStatus(LinuxBasePhase.IDLE, 0, installed = false)
    }

    /**
     * Extracts a rootfs tarball (plain tar, gzip-, or xz-compressed — detected
     * via magic bytes) into [targetDir].
     *
     * TWO-PASS by design: pass 1 materializes directories + regular files, pass
     * 2 creates symlinks/hardlinks. This guarantees a link's target already
     * exists when the fallback (copy instead of symlink) kicks in, regardless of
     * tar entry order. Device nodes/FIFOs are skipped (Android app storage
     * cannot create them and the guest does not need them). Returns the number
     * of extracted entries.
     */
    internal fun extractRootfs(source: InputStream, targetDir: File, onBytes: (Long) -> Unit): Int {
        val canonicalTarget = targetDir.canonicalFile
        var entries = 0
        var bytes = 0L
        var skippedHostPaths = 0
        val deferredLinks = mutableListOf<Pair<File, TarArchiveEntry>>()
        val failures = mutableListOf<String>()

        val pushback = java.io.PushbackInputStream(source.buffered(), 6)
        val magic = ByteArray(6)
        var magicRead = 0
        while (magicRead < 6) {
            val r = pushback.read(magic, magicRead, 6 - magicRead)
            if (r < 0) break
            magicRead += r
        }
        if (magicRead > 0) pushback.unread(magic, 0, magicRead)
        val isGzip = magicRead >= 2 &&
            magic[0] == GZIP_MAGIC_1 && magic[1] == GZIP_MAGIC_2
        val isXz = magicRead >= 6 &&
            magic[0] == XZ_MAGIC_1 && magic[1] == '7'.code.toByte() && magic[2] == 'z'.code.toByte() &&
            magic[3] == 'X'.code.toByte() && magic[4] == 'Z'.code.toByte() && magic[5] == 0.toByte()
        val decompressed: InputStream = when {
            isXz -> XZCompressorInputStream(pushback)
            isGzip -> GzipCompressorInputStream(pushback)
            else -> pushback
        }
        val tar = TarArchiveInputStream(decompressed)

        // Pass 1: directories + regular files.
        var entry: TarArchiveEntry? = tar.nextEntry
        while (entry != null) {
            val name = entry.name
            try {
                val out = resolveSafe(canonicalTarget, name)
                if (out == null) {
                    // Name is valid on Linux but unrepresentable on this host
                    // filesystem (e.g. ':arm64' multiarch suffixes on Windows
                    // test JVMs). On Android this never triggers. Skip it.
                    skippedHostPaths++
                    entries++
                    entry = tar.nextEntry
                    continue
                }
                when {
                    entry.isDirectory -> out.mkdirs()
                    entry.isSymbolicLink || entry.isLink -> {
                        out.parentFile?.mkdirs()
                        deferredLinks += out to entry
                    }
                    entry.isFile -> {
                        out.parentFile?.mkdirs()
                        out.outputStream().use { os -> tar.copyTo(os) }
                        bytes += out.length()
                        onBytes(bytes)
                        if (entry.mode and 0b001_001_001 != 0) {
                            runCatching { out.setExecutable(true, false) }
                        }
                    }
                    else -> {
                        // Device nodes, FIFOs, sockets: cannot exist in app
                        // storage and are not needed under proot.
                        out.parentFile?.mkdirs()
                    }
                }
                entries++
            } catch (t: Throwable) {
                failures += "$name: ${t.message ?: t.javaClass.simpleName}"
            }
            entry = tar.nextEntry
        }

        // Pass 2: links, now that every target file exists.
        for ((out, linkEntry) in deferredLinks) {
            try {
                if (linkEntry.isSymbolicLink) {
                    runCatching { linkOrCopy(out, linkEntry.linkName, canonicalTarget) }
                        .getOrElse { e ->
                            // Case-insensitive host FS (Windows tests): the link
                            // name can collide with existing content. If the path
                            // already has content, the guest sees it anyway.
                            if (!out.exists()) throw e
                        }
                } else {
                    val hardTarget = runCatching { resolveSafe(canonicalTarget, linkEntry.linkName) }.getOrNull()
                    when {
                        hardTarget == null || !hardTarget.exists() -> out.writeBytes(ByteArray(0))
                        out.canonicalFile.path.equals(hardTarget.canonicalFile.path, ignoreCase = true) -> {
                            // Case-insensitive host FS (Windows tests): the link's
                            // name IS its target (e.g. pam.7.gz / PAM.7.gz).
                            // Content is already in place; nothing to do.
                        }
                        else -> runCatching { java.nio.file.Files.createLink(out.toPath(), hardTarget.toPath()) }
                            .getOrElse { hardTarget.copyTo(out, overwrite = true) }
                    }
                }
                entries++
            } catch (t: Throwable) {
                failures += "${linkEntry.name} (link): ${t.message ?: t.javaClass.simpleName}"
            }
        }

        if (skippedHostPaths > 0) {
            Timber.i("extractRootfs: skipped %d entries unrepresentable on this host fs", skippedHostPaths)
        }

        if (failures.isNotEmpty()) {
            throw IllegalStateException(
                "${failures.size} entr${if (failures.size == 1) "y" else "ies"} failed to extract — " +
                    failures.take(5).joinToString("; ")
            )
        }
        return entries
    }

    private fun resolveSafe(target: File, name: String): File? {
        val cleaned = name.removePrefix("./").removePrefix("/")
        val candidate = try {
            File(target, cleaned).canonicalFile
        } catch (io: java.io.IOException) {
            // Host filesystem cannot represent this (Linux-valid) name, e.g.
            // ':' in dpkg multiarch file names when running on Windows.
            return null
        }
        return if (candidate.path == target.path || candidate.path.startsWith(target.path + File.separator)) {
            candidate
        } else {
            throw IllegalStateException("entry escapes target: $name")
        }
    }

    private fun linkOrCopy(linkPath: File, linkTarget: String, rootfs: File) {
        val nio = runCatching {
            java.nio.file.Files.createSymbolicLink(linkPath.toPath(), java.nio.file.Paths.get(linkTarget))
        }
        if (nio.isSuccess) return
        // Fallback (non-POSIX test hosts): copy the resolved target if present.
        val resolved = if (File(linkTarget).isAbsolute) {
            File(rootfs, File(linkTarget).path.removePrefix("/"))
        } else {
            File(linkPath.parentFile, linkTarget)
        }.canonicalFile
        if (resolved.path.equals(linkPath.canonicalFile.path, ignoreCase = true)) {
            // Case-insensitive host FS: link name collides with its target;
            // the content is already in place.
            return
        }
        if (resolved.exists() && resolved.isFile) {
            resolved.copyTo(linkPath, overwrite = true)
            runCatching { linkPath.setExecutable(true, false) }
        } else {
            linkPath.writeText("")
        }
    }

    /**
     * DNS + apt hygiene so `apt-get install` / `npm install` / `pip` have
     * network access and package installs never try to start daemons or drop
     * privileges (both impossible under proot).
     */
    internal fun configureRootfs(rootfs: File) {
        // /etc/resolv.conf: the Debian image ships a symlink into /run/systemd
        // (systemd-resolved), which is dead under proot. Replace with a real file.
        val dns = deviceDnsServers()
        val resolv = File(rootfs, "etc/resolv.conf")
        resolv.parentFile?.mkdirs()
        runCatching { java.nio.file.Files.deleteIfExists(resolv.toPath()) }
        resolv.writeText(dns.joinToString("\n") { "nameserver $it" } + "\n")

        File(rootfs, "tmp").mkdirs()
        File(rootfs, "root").mkdirs()
        File(rootfs, "var/cache/apt/archives/partial").mkdirs()
        File(rootfs, "var/lock").mkdirs()
        File(rootfs, "run/lock").mkdirs()

        // /etc/hosts sanity for localhost-bound dev servers.
        val hosts = File(rootfs, "etc/hosts")
        if (!hosts.exists()) {
            hosts.writeText("127.0.0.1 localhost\n::1 localhost\n")
        }

        // apt's download sandbox user (_apt) cannot exist under proot; force root.
        val aptConf = File(rootfs, "etc/apt/apt.conf.d/99androllm-proot")
        aptConf.parentFile?.mkdirs()
        aptConf.writeText("APT::Sandbox::User \"root\";\n")

        // Never let package postinst scripts start system services — there is
        // no init under proot, and the attempts can fail the whole install.
        val policyRc = File(rootfs, "usr/sbin/policy-rc.d")
        policyRc.parentFile?.mkdirs()
        policyRc.writeText("#!/bin/sh\nexit 101\n")
        runCatching { policyRc.setExecutable(true, false) }

        // Make sure apt has repositories. Official images already do; this is
        // the safety net for stripped images.
        val sourcesList = File(rootfs, "etc/apt/sources.list")
        val sourcesDir = File(rootfs, "etc/apt/sources.list.d")
        val hasSources = (sourcesList.exists() && sourcesList.readText().any { it == 'd' }) ||
            (sourcesDir.exists() && (sourcesDir.listFiles()?.any { it.name.endsWith(".list") || it.name.endsWith(".sources") } == true))
        if (!hasSources) {
            sourcesList.writeText(
                "deb http://deb.debian.org/debian trixie main\n" +
                    "deb http://deb.debian.org/debian trixie-updates main\n" +
                    "deb http://deb.debian.org/debian-security trixie-security main\n"
            )
        }
    }

    /** Best-effort device DNS (hidden SystemProperties API) with sane fallbacks. */
    private fun deviceDnsServers(): List<String> {
        val fromDevice = listOf("net.dns1", "net.dns2").mapNotNull { key ->
            runCatching {
                val clazz = Class.forName("android.os.SystemProperties")
                val get = clazz.getMethod("get", String::class.java)
                (get.invoke(null, key) as? String)?.takeIf { it.isNotBlank() }
            }.getOrNull()
        }
        return (fromDevice + listOf("8.8.8.8", "1.1.1.1")).distinct().take(4)
    }

    companion object {
        const val MARKER = ".base-ready"
        const val MARKER_FLAVOR = "debian-trixie-rootfs"
        const val DEFAULT_EXPECTED_ROOTFS_BYTES = 300_000_000L
        private val GZIP_MAGIC_1: Byte = 0x1F
        private val GZIP_MAGIC_2: Byte = 0x8B.toByte()
        private val XZ_MAGIC_1: Byte = 0xFD.toByte()
    }
}
