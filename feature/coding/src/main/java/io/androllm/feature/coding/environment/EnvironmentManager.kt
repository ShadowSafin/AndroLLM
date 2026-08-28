package io.androllm.feature.coding.environment

import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Physical installer for one addon. Production implements this by downloading +
 * extracting the toolchain archive for the device ABI; tests (and the offline
 * fallback) use [SimulatedAddonInstaller]. Kept as an interface so the manager
 * logic (dependency chains, progress, retry, PATH wiring) is JVM-testable.
 */
fun interface AddonInstallerBackend {
    /** Installs [pkg] into [installDir]; reports progress; returns success. */
    suspend fun install(pkg: RuntimePackage, installDir: File, onProgress: (InstallProgress) -> Unit): Boolean
}

/**
 * Offline/test installer: materializes the addon directory with a `bin/` folder,
 * a launcher script for each provided command and an `.installed` marker. This
 * keeps the install-state logic real and verifiable without a network download,
 * and acts as the on-device fallback when no artifact URL is configured.
 *
 * IMPORTANT (Android reality): app-private storage is mounted noexec, so the
 * launcher scripts are NOT meant to be exec'd via PATH. [LocalShellBackend]
 * wraps each command in a shell function that runs the launcher through `sh`
 * (read, not exec). The launcher itself looks for a real device-native binary
 * under `<addon>/lib/<cmd>`; when absent (this simulated build) it prints a
 * clear, honest explanation and exits 127 — never a confusing "Permission denied".
 */
class SimulatedAddonInstaller : AddonInstallerBackend {
    override suspend fun install(
        pkg: RuntimePackage,
        installDir: File,
        onProgress: (InstallProgress) -> Unit
    ): Boolean {
        return runCatching {
            onProgress(InstallProgress(pkg.id, InstallStatus.DOWNLOADING, 20, message = "Fetching ${pkg.name}"))
            installDir.mkdirs()
            onProgress(InstallProgress(pkg.id, InstallStatus.EXTRACTING, 60, message = "Extracting ${pkg.name}"))
            val bin = File(installDir, "bin")
            bin.mkdirs()
            for (cmd in pkg.providesCommands) {
                val launcher = File(bin, cmd)
                launcher.writeText(launcherScript(pkg, cmd))
                runCatching { launcher.setExecutable(true) }
            }
            File(installDir, MARKER).writeText("${pkg.id} ${pkg.version}\n")
            onProgress(InstallProgress(pkg.id, InstallStatus.INSTALLED, 100, message = "${pkg.name} installed"))
            true
        }.getOrElse {
            onProgress(InstallProgress(pkg.id, InstallStatus.FAILED, 0, message = it.message.orEmpty()))
            false
        }
    }

    /**
     * Launcher script for one provided command. It prefers a real binary at
     * `<addon>/lib/<cmd>` (where a future real installer can place a
     * device-compatible runtime) and otherwise reports honestly that this build
     * ships no runnable runtime for the addon.
     */
    internal fun launcherScript(pkg: RuntimePackage, cmd: String): String =
        """
        |#!/bin/sh
        |# ${pkg.name} ${pkg.version} — '$cmd' launcher
        |ADDON_DIR=${'$'}(CDPATH= cd -- "${'$'}(dirname -- "$0")/.." && pwd)
        |REAL_BIN="${'$'}ADDON_DIR/lib/$cmd"
        |if [ -f "${'$'}REAL_BIN" ]; then
        |  exec "${'$'}REAL_BIN" "$@"
        |fi
        |echo "$cmd: the '${pkg.id}' addon in this build is a placeholder — no real ${pkg.name} runtime binary is bundled." >&2
        |echo "Android forbids executing downloaded binaries from app storage (noexec), so '$cmd' cannot actually run until a device-compatible ${pkg.name} runtime is shipped with the app." >&2
        |exit 127
        |
        """.trimMargin()

    companion object {
        const val MARKER = ".installed"
    }
}

/**
 * Manages the coding Linux environment's installed addons.
 *
 * Responsibilities:
 *  - Track which addons are installed (file markers under the env root).
 *  - Install an addon AND its dependency chain (nodejs before pnpm/yarn, java
 *    before gradle), reporting [InstallProgress] on a [StateFlow].
 *  - Retry a failed install.
 *  - Uninstall.
 *  - Expose the `bin/` directories of installed addons so [LocalShellBackend]
 *    can prepend them to PATH (that is how `npm`/`python`/`git` become runnable).
 *
 * State lives on the filesystem (one directory per addon + an `.installed`
 * marker) so it survives process death and is trivially testable with a temp dir.
 */
class EnvironmentManager(
    private val envRoot: () -> File,
    private val installer: AddonInstallerBackend = SimulatedAddonInstaller()
) {
    private val _installed = MutableStateFlow<Set<String>>(emptySet())
    val installed: StateFlow<Set<String>> = _installed.asStateFlow()

    private val _progress = MutableStateFlow<Map<String, InstallProgress>>(emptyMap())
    val progress: StateFlow<Map<String, InstallProgress>> = _progress.asStateFlow()

    init {
        refreshInstalled()
    }

    private fun addonsDir(): File = File(envRoot().apply { mkdirs() }, "addons")

    private fun addonDir(id: String): File = File(addonsDir(), id)

    private fun marker(id: String): File = File(addonDir(id), SimulatedAddonInstaller.MARKER)

    /** Scans the env root and rebuilds the installed set. */
    fun refreshInstalled() {
        val present = addonsDir().listFiles()?.toList().orEmpty()
            .filter { it.isDirectory && File(it, SimulatedAddonInstaller.MARKER).exists() }
            .map { it.name }
            .toSet()
        _installed.value = present
    }

    fun isInstalled(id: String): Boolean = id in _installed.value

    fun installedAddons(): Set<String> = _installed.value

    /** `bin/` dirs of installed addons, in stable order — prepended to PATH. */
    fun pathEntries(): List<String> =
        _installed.value.sorted().map { File(addonDir(it), "bin").path }

    /**
     * Shell-function wrappers for every command provided by an installed addon:
     * command → absolute launcher path. [LocalShellBackend] turns these into
     * `cmd() { sh '<launcher>' "$@"; }` definitions so launchers are READ via sh
     * instead of exec'd (Android blocks executing files in app storage). Only
     * used on the native-shell fallback path — inside the proot Linux base the
     * real binaries are on PATH directly.
     */
    fun shellFunctionWrappers(): Map<String, String> {
        val wrappers = mutableMapOf<String, String>()
        for (addonId in _installed.value.sorted()) {
            val pkg = MarketplaceCatalog.find(addonId) ?: continue
            for (cmd in pkg.providesCommands) {
                wrappers[cmd] = File(addonDir(addonId), "bin/$cmd").path
            }
        }
        return wrappers
    }

    /**
     * Installs [addonId] and any uninstalled dependencies (deps first). Returns
     * true when the whole chain is installed. Progress is published per addon.
     */
    suspend fun install(addonId: String): Boolean {
        val pkg = MarketplaceCatalog.find(addonId) ?: return false
        val chain = MarketplaceCatalog.dependencyChain(addonId)
        for (candidate in chain) {
            if (isInstalled(candidate.id)) continue
            if (!installOne(candidate)) return false
        }
        return isInstalled(pkg.id)
    }

    private suspend fun installOne(pkg: RuntimePackage): Boolean {
        val dir = addonDir(pkg.id)
        dir.mkdirs()
        val ok = runCatching {
            installer.install(pkg, dir) { p ->
                _progress.value = _progress.value + (pkg.id to p)
            }
        }.getOrDefault(false)

        if (ok) {
            refreshInstalled()
        } else {
            _progress.value = _progress.value +
                (pkg.id to InstallProgress(pkg.id, InstallStatus.FAILED, 0, message = "Install failed for ${pkg.name}"))
        }
        return ok
    }

    /** Retries a previously failed (or interrupted) install. */
    suspend fun retryInstall(addonId: String): Boolean {
        _progress.value = _progress.value +
            (addonId to InstallProgress(addonId, InstallStatus.DOWNLOADING, 0, message = "Retrying"))
        return install(addonId)
    }

    /** Removes an addon's directory and updates the installed set. */
    suspend fun uninstall(addonId: String) {
        addonDir(addonId).deleteRecursively()
        _progress.value = _progress.value - addonId
        refreshInstalled()
    }

    /** Current install status for one addon. */
    fun statusOf(addonId: String): InstallStatus = when {
        isInstalled(addonId) -> InstallStatus.INSTALLED
        else -> _progress.value[addonId]?.status ?: InstallStatus.NOT_INSTALLED
    }
}
