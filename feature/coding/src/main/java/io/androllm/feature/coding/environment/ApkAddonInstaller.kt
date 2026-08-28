package io.androllm.feature.coding.environment

import java.io.File
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/**
 * REAL addon installer: installs runtimes by running `apt-get install` inside
 * the proot'd Debian base. This is what makes `npm`, `python`, `git`, `go`,
 * etc. genuinely execute — Debian's package manager fetches and installs the
 * actual prebuilt binaries into the rootfs, and proot runs them.
 *
 * Requires the Linux base to be provisioned first ([LinuxBaseManager]). If it
 * is not, install fails fast with a clear message so the UI can prompt the user
 * to provision the base.
 *
 * Addon ids from [MarketplaceCatalog] map to Debian package names below. Where
 * a marketplace addon bundles several commands (e.g. Node.js → node+npm), we
 * install the corresponding set of apt packages.
 */
class ApkAddonInstaller(
    private val base: LinuxBaseManager,
    private val shell: ShellBackend,
    private val scratchDir: File,
    private val timeoutMs: Long = DEFAULT_INSTALL_TIMEOUT_MS
) : AddonInstallerBackend {

    override suspend fun install(
        pkg: RuntimePackage,
        installDir: File,
        onProgress: (InstallProgress) -> Unit
    ): Boolean {
        if (!base.isInstalled()) {
            onProgress(
                InstallProgress(pkg.id, InstallStatus.FAILED, 0, message = "Provision the Linux base first (Environment panel).")
            )
            return false
        }
        val apkPackages = APK_PACKAGES[pkg.id]
        if (apkPackages.isNullOrEmpty()) {
            onProgress(InstallProgress(pkg.id, InstallStatus.FAILED, 0, message = "No Debian package mapping for '${pkg.id}'."))
            return false
        }

        onProgress(InstallProgress(pkg.id, InstallStatus.DOWNLOADING, 15, message = "apt-get install ${apkPackages.joinToString(" ")}"))
        scratchDir.mkdirs()

        // Heal any interrupted dpkg state first (a killed/timed-out install
        // leaves packages unpacked-but-unconfigured, after which apt refuses
        // to run), then refresh indexes, then install. --no-install-recommends
        // keeps Debian from pulling in docs/X11/recommendation bloat;
        // DEBIAN_FRONTEND keeps debconf non-interactive under proot.
        val command = "dpkg --configure -a && apt-get update && " +
            "DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends ${apkPackages.joinToString(" ")}"
        Timber.i("apt install start: %s", command)
        val result = withTimeoutOrNull(timeoutMs) {
            shell.run(command, scratchDir)
        }

        if (result == null) {
            shell.cancelCurrent()
            Timber.w("apt install timed out after %d ms: %s", timeoutMs, command)
            onProgress(InstallProgress(pkg.id, InstallStatus.FAILED, 0, message = "${pkg.name} install timed out."))
            return false
        }
        if (result.exitCode != 0) {
            val tail = result.combinedOutput.lines().takeLast(6).joinToString("\n")
            Timber.w("apt install failed exit=%d:\n%s", result.exitCode, result.combinedOutput)
            onProgress(
                InstallProgress(pkg.id, InstallStatus.FAILED, 0, message = "apt failed (exit ${result.exitCode}):\n$tail")
            )
            return false
        }
        Timber.i("apt install OK: %s", command)

        // Record success so EnvironmentManager's state + PATH logic see it. The
        // binaries live in the rootfs /usr/bin (already on proot's PATH); we keep
        // the addon dir + marker purely for bookkeeping parity with the simulator.
        installDir.mkdirs()
        File(installDir, SimulatedAddonInstaller.MARKER).writeText("${pkg.id} ${pkg.version} (apt)\n")
        onProgress(InstallProgress(pkg.id, InstallStatus.INSTALLED, 100, message = "${pkg.name} installed via apt"))
        return true
    }

    companion object {
        const val DEFAULT_INSTALL_TIMEOUT_MS = 900_000L

        /** Marketplace addon id → Debian package names. */
        val APK_PACKAGES: Map<String, List<String>> = mapOf(
            "nodejs" to listOf("nodejs", "npm"),
            "pnpm" to listOf("pnpm"),
            "yarn" to listOf("yarnpkg"),
            "python" to listOf("python3", "python3-pip", "python3-venv"),
            "git" to listOf("git", "git-lfs"),
            "java" to listOf("openjdk-17-jdk-headless"),
            "gradle" to listOf("gradle"),
            "go" to listOf("golang-go"),
            "rust" to listOf("rustc", "cargo"),
            "build-tools" to listOf("build-essential", "cmake", "ninja-build", "make", "pkg-config"),
            "linux-utils" to listOf("curl", "wget", "jq", "tree", "less", "vim", "grep", "sed", "coreutils", "findutils", "ca-certificates", "gawk", "unzip")
        )
    }
}

/**
 * Chooses the real [ApkAddonInstaller] when the Linux base is provisioned, and
 * auto-provisions the base on demand so "install Node.js" works end-to-end. If
 * provisioning fails (e.g. corrupt bundled rootfs), it degrades to the offline
 * [SimulatedAddonInstaller] placeholder so the marketplace is never dead.
 */
class DelegatingAddonInstaller(
    private val base: LinuxBaseManager,
    private val apk: ApkAddonInstaller,
    private val simulated: SimulatedAddonInstaller = SimulatedAddonInstaller()
) : AddonInstallerBackend {
    override suspend fun install(
        pkg: RuntimePackage,
        installDir: File,
        onProgress: (InstallProgress) -> Unit
    ): Boolean {
        if (!base.isInstalled()) {
            onProgress(InstallProgress(pkg.id, InstallStatus.DOWNLOADING, 5, message = "Preparing the Linux base first..."))
            val provisioned = base.provision()
            if (!provisioned) {
                onProgress(InstallProgress(pkg.id, InstallStatus.DOWNLOADING, 8, message = "Base unavailable — using offline placeholder"))
                return simulated.install(pkg, installDir, onProgress)
            }
        }
        return apk.install(pkg, installDir, onProgress)
    }
}
