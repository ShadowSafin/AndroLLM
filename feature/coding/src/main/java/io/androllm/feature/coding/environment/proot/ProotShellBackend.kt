package io.androllm.feature.coding.environment.proot

import io.androllm.feature.coding.environment.CommandResult
import io.androllm.feature.coding.environment.LocalShellBackend
import io.androllm.feature.coding.environment.ShellBackend
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * [ShellBackend] that runs every command inside the proot'd Linux rootfs —
 * a real Debian userland where `npm`, `python`, `git`, `gcc`... genuinely
 * execute (installed via `apt` by the marketplace).
 *
 * proot itself is exec'd from the app's native library dir (allowed by
 * Android); guest binaries are loaded from the noexec rootfs through proot's
 * loader, which maps them into memory instead of exec'ing them.
 *
 * Output is captured verbatim on both streams (read concurrently so a full
 * pipe buffer can never deadlock the child) — raw build logs reach the user
 * and the model exactly as the toolchain emitted them.
 */
class ProotShellBackend(
    private val files: ProotFiles,
    private val baseReady: () -> Boolean
) : ShellBackend {

    @Volatile
    private var current: Process? = null

    override val label: String = "proot-debian"

    /** True when commands can currently run inside the Linux base. */
    fun available(): Boolean = baseReady() && files.binariesPresent()

    override suspend fun run(
        command: String,
        workingDir: File,
        env: Map<String, String>
    ): CommandResult = withContext(Dispatchers.IO) {
        if (!available()) {
            return@withContext unavailableResult(command, workingDir)
        }
        val started = System.currentTimeMillis()
        val process = runCatching { startProcess(command, workingDir, env) }.getOrElse {
            Timber.e(it, "proot failed to start")
            return@withContext CommandResult(
                command = command,
                exitCode = 127,
                stderr = "Failed to start proot: ${it.message}",
                durationMs = System.currentTimeMillis() - started,
                workingDir = workingDir.path
            )
        }
        current = process

        // Drain both streams concurrently to avoid pipe-buffer deadlock.
        val drained = try {
            coroutineScope {
                val outJob = async { process.inputStream.bufferedReader().readText() }
                val errJob = async { process.errorStream.bufferedReader().readText() }
                val code = process.waitFor()
                Triple(outJob.await(), errJob.await(), code)
            }
        } catch (ce: CancellationException) {
            process.destroyForcibly()
            throw ce
        } finally {
            current = null
        }

        Timber.i(
            "proot done: exit=%d dur=%dms outTail=%s errTail=%s",
            drained.third,
            System.currentTimeMillis() - started,
            drained.first.trim().lines().takeLast(3).joinToString(" | "),
            drained.second.trim().lines().takeLast(3).joinToString(" | ")
        )

        CommandResult(
            command = command,
            exitCode = drained.third,
            stdout = drained.first,
            stderr = drained.second,
            cancelled = false,
            durationMs = System.currentTimeMillis() - started,
            workingDir = workingDir.path
        )
    }

    override suspend fun runStreaming(
        command: String,
        workingDir: File,
        env: Map<String, String>,
        onLine: (String) -> Unit
    ): CommandResult = withContext(Dispatchers.IO) {
        if (!available()) {
            val result = unavailableResult(command, workingDir)
            result.combinedOutput.lines().forEach(onLine)
            return@withContext result
        }
        val started = System.currentTimeMillis()
        val process = runCatching { startProcess(command, workingDir, env) }.getOrElse {
            Timber.e(it, "proot failed to start")
            return@withContext CommandResult(
                command = command,
                exitCode = 127,
                stderr = "Failed to start proot: ${it.message}",
                durationMs = System.currentTimeMillis() - started,
                workingDir = workingDir.path
            )
        }
        current = process
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        try {
            coroutineScope {
                val outJob = async { LocalShellBackend.pumpLines(process.inputStream, stdout, onLine) }
                val errJob = async { LocalShellBackend.pumpLines(process.errorStream, stderr, onLine) }
                val code = process.waitFor()
                outJob.await()
                errJob.await()
                CommandResult(
                    command = command,
                    exitCode = code,
                    stdout = stdout.toString(),
                    stderr = stderr.toString(),
                    cancelled = false,
                    durationMs = System.currentTimeMillis() - started,
                    workingDir = workingDir.path
                )
            }
        } catch (ce: CancellationException) {
            process.destroyForcibly()
            throw ce
        } finally {
            current = null
        }
    }

    override suspend fun spawn(
        command: String,
        workingDir: File,
        env: Map<String, String>
    ): Process = withContext(Dispatchers.IO) {
        check(available()) { "Linux base environment is not provisioned yet." }
        startProcess(command, workingDir, env).also {
            Timber.i("proot spawn: cmd=%s ws=%s", command, workingDir.absolutePath)
        }
    }

    /**
     * Builds and starts the proot child process shared by run/runStreaming/spawn.
     *
     * The name-resolution shim is preloaded into the guest command. Exporting
     * LD_PRELOAD inside the guest shell (rather than in proot's own environment)
     * means Bionic's linker never tries to load the guest-libc shim into the
     * proot process itself — only the guest's tools (apt/npm/pip/git) pick it up.
     */
    private fun startProcess(command: String, workingDir: File, env: Map<String, String>): Process {
        files.tmpDir.mkdirs()
        val shimReady = files.ensureShimInRootfs()
        val guestCommand = if (shimReady) "export LD_PRELOAD=${files.shimGuestPath}; $command" else command
        val tokens = ProotCommandLine.argv(
            prootBinary = files.prootBinary.absolutePath,
            rootfs = files.rootfsDir.absolutePath,
            workspacePath = workingDir.absolutePath,
            command = guestCommand
        )
        Timber.i("proot run: cmd=%s rootfs=%s ws=%s shim=%s", command, files.rootfsDir.absolutePath, workingDir.absolutePath, shimReady)
        val builder = ProcessBuilder(tokens)
            .directory(files.tmpDir)
            .redirectErrorStream(false)
        builder.environment().let { e ->
            e += ProotCommandLine.env(
                nativeLibDir = files.nativeLibDir.absolutePath,
                loaderPath = files.loader.absolutePath,
                tmpDir = files.tmpDir.absolutePath
            )
            env.forEach { (k, v) -> e[k] = v }
        }
        return builder.start()
    }

    private fun unavailableResult(command: String, workingDir: File) = CommandResult(
        command = command,
        exitCode = 127,
        stderr = "Linux base environment is not provisioned yet. Open Environment and install it, then retry.",
        workingDir = workingDir.path
    )

    override fun cancelCurrent() {
        // --kill-on-exit ensures the guest tree dies when proot is destroyed.
        current?.destroyForcibly()
        current = null
    }
}

/**
 * Routes commands to the proot Linux base when it is provisioned, and to the
 * device's native shell otherwise (so basic commands keep working before the
 * base is installed). Selection happens per command, so provisioning the base
 * mid-session takes effect immediately.
 */
class DelegatingShellBackend(
    private val proot: ProotShellBackend,
    private val local: ShellBackend,
    private val preferProot: () -> Boolean = { true }
) : ShellBackend {

    override val label: String get() = if (active() === proot) proot.label else local.label

    fun active(): ShellBackend = if (preferProot() && proot.available()) proot else local

    override suspend fun run(command: String, workingDir: File, env: Map<String, String>): CommandResult =
        active().run(command, workingDir, env)

    override suspend fun runStreaming(
        command: String,
        workingDir: File,
        env: Map<String, String>,
        onLine: (String) -> Unit
    ): CommandResult = active().runStreaming(command, workingDir, env, onLine)

    override suspend fun spawn(command: String, workingDir: File, env: Map<String, String>): Process =
        active().spawn(command, workingDir, env)

    override fun cancelCurrent() {
        proot.cancelCurrent()
        local.cancelCurrent()
    }
}
