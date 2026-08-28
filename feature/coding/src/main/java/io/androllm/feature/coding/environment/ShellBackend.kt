package io.androllm.feature.coding.environment

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * The actual process runner. The coding agent never talks to [ProcessBuilder]
 * directly — it goes through [CommandExecutor], which delegates the physical
 * execution here. Splitting it out lets the executor's safety / dependency
 * logic run on the JVM against a [FakeShellBackend] while production uses the
 * real [LocalShellBackend].
 */
interface ShellBackend {
    /** Runs [command] in [workingDir] with extra [env]; returns the raw result. */
    suspend fun run(command: String, workingDir: File, env: Map<String, String> = emptyMap()): CommandResult

    /**
     * Streaming variant of [run]: [onLine] receives output lines (stdout and
     * stderr interleaved in arrival order) AS THEY ARE PRODUCED, so the UI can
     * show live command output. The returned [CommandResult] still carries the
     * complete raw output. Default implementation falls back to [run] and emits
     * the output after the fact (used by fakes).
     */
    suspend fun runStreaming(
        command: String,
        workingDir: File,
        env: Map<String, String> = emptyMap(),
        onLine: (String) -> Unit
    ): CommandResult {
        val result = run(command, workingDir, env)
        result.combinedOutput.lines().forEach(onLine)
        return result
    }

    /**
     * Starts [command] detached and returns the live process WITHOUT waiting for
     * it to exit — used for background services (dev servers, watchers). The
     * caller owns the process (drain its streams, destroy it to stop it). The
     * spawned process is NOT tracked as the "current" command, so
     * [cancelCurrent] does not kill background services.
     */
    suspend fun spawn(command: String, workingDir: File, env: Map<String, String> = emptyMap()): Process

    /** Destroys the currently running process, if any (cancellation). */
    fun cancelCurrent()

    /** Human-readable backend label for the environment panel. */
    val label: String
}

/**
 * Builds the POSIX shell prelude that makes addon-provided commands callable
 * WITHOUT relying on the execute bit. Android 10+ forbids `execve()` of files
 * in app-private storage (SELinux noexec on `app_data_file`), so a launcher
 * script found via PATH would fail with "Permission denied". Instead we define
 * a shell function per command that runs the launcher through `sh` — the shell
 * READS the script (allowed) rather than executing it (blocked).
 *
 * Output looks like:
 * ```
 * npm() { sh '/data/.../addons/nodejs/bin/npm' "$@"; }
 * ```
 */
fun buildShellPrelude(commandWrappers: Map<String, String>): String {
    if (commandWrappers.isEmpty()) return ""
    return commandWrappers.entries.sortedBy { it.key }.joinToString("\n") { (cmd, launcher) ->
        val quoted = launcher.replace("'", "'\\''")
        "$cmd() { sh '$quoted' \"\$@\"; }"
    } + "\n"
}

/**
 * Production shell: runs commands through the device's Linux userland via
 * `sh -c`. Android's kernel is Linux and `/system/bin/sh` (toybox) provides the
 * standard applets (ls, cat, grep, find, sed, mkdir, rm, cp, mv, echo, touch),
 * so the workspace CLI works out of the box for core operations; richer
 * toolchains (node, python, git, gradle...) arrive via marketplace addons.
 *
 * Addon commands are made available two ways:
 *  - [extraPathEntries] prepends addon `bin/` dirs to PATH (works on JVM/desktop).
 *  - [commandWrappers] defines shell functions that invoke each addon launcher
 *    through `sh` — required on Android, where executing files from app storage
 *    is blocked (noexec) and a plain PATH lookup would die with "Permission denied".
 *
 * Output is captured verbatim on both streams (read concurrently so a full pipe
 * buffer can never deadlock the child). The shell is auto-selected so the same
 * class also runs on a desktop JVM (for local tests): `cmd /c` on Windows,
 * `sh -c` everywhere else. On Android this is always `sh -c`.
 */
class LocalShellBackend(
    private val extraPathEntries: () -> List<String> = { emptyList() },
    private val commandWrappers: () -> Map<String, String> = { emptyMap() }
) : ShellBackend {

    @Volatile
    private var current: Process? = null

    override val label: String = "local-sh"

    private fun isWindows(): Boolean = System.getProperty("os.name").orEmpty().lowercase().contains("win")

    private fun shellTokens(): List<String> =
        if (isWindows()) listOf("cmd.exe", "/c") else listOf("sh", "-c")

    override suspend fun run(
        command: String,
        workingDir: File,
        env: Map<String, String>
    ): CommandResult = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        val process = runCatching { startProcess(command, workingDir, env) }.getOrElse {
            return@withContext CommandResult(
                command = command,
                exitCode = 127,
                stderr = "Failed to start shell: ${it.message}",
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
        val started = System.currentTimeMillis()
        val process = runCatching { startProcess(command, workingDir, env) }.getOrElse {
            return@withContext CommandResult(
                command = command,
                exitCode = 127,
                stderr = "Failed to start shell: ${it.message}",
                durationMs = System.currentTimeMillis() - started,
                workingDir = workingDir.path
            )
        }
        current = process
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        try {
            coroutineScope {
                val outJob = async { pumpLines(process.inputStream, stdout, onLine) }
                val errJob = async { pumpLines(process.errorStream, stderr, onLine) }
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
        startProcess(command, workingDir, env)
    }

    /** Builds and starts the child process shared by run/runStreaming/spawn. */
    private fun startProcess(command: String, workingDir: File, env: Map<String, String>): Process {
        // On a POSIX shell, prepend the function wrappers so addon launchers are
        // read via `sh` instead of exec'd (Android noexec would deny the exec).
        val effectiveCommand = if (!isWindows()) {
            buildShellPrelude(runCatching { commandWrappers() }.getOrDefault(emptyMap())) + command
        } else {
            command
        }
        val tokens = shellTokens() + effectiveCommand
        val dir = workingDir.takeIf { it.exists() && it.isDirectory } ?: workingDir.parentFile
        val builder = ProcessBuilder(tokens)
            .directory(dir)
            .redirectErrorStream(false)

        // Prepend addon-provided PATH entries so installed runtimes resolve.
        val pathPrefix = extraPathEntries().joinToString(File.pathSeparator)
        builder.environment().let { e ->
            if (pathPrefix.isNotEmpty()) {
                e["PATH"] = pathPrefix + File.pathSeparator + (e["PATH"] ?: "")
            }
            env.forEach { (k, v) -> e[k] = v }
        }
        return builder.start()
    }

    override fun cancelCurrent() {
        current?.destroyForcibly()
        current = null
    }

    companion object {
        /** Reads [stream] line by line, appending to [sink] and invoking [onLine] live. */
        internal fun pumpLines(stream: InputStream, sink: StringBuilder, onLine: (String) -> Unit) {
            stream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    synchronized(sink) {
                        if (sink.isNotEmpty()) sink.append('\n')
                        sink.append(line)
                    }
                    runCatching { onLine(line) }
                }
            }
        }
    }
}

/**
 * Deterministic in-memory [ShellBackend] for unit tests and previews. Records
 * every command and replays canned results; a default responder can simulate
 * real behavior (e.g. echo) without spawning processes.
 */
class FakeShellBackend(
    private val responder: (command: String, workingDir: File) -> CommandResult? = { _, _ -> null }
) : ShellBackend {

    data class Invocation(val command: String, val workingDir: File, val env: Map<String, String>)

    val invocations = mutableListOf<Invocation>()
    var cancelCount = 0
        private set

    /** Produces the process returned by [spawn] (tests can script output/lifetime). */
    var spawnResponder: (command: String) -> Process = { FakeProcess() }

    override val label: String = "fake-sh"

    override suspend fun run(command: String, workingDir: File, env: Map<String, String>): CommandResult {
        invocations += Invocation(command, workingDir, env)
        val canned = responder(command, workingDir)
        if (canned != null) return canned.copy(command = command, workingDir = workingDir.path)
        // Default: simulate a successful echo of the command so flows complete.
        return CommandResult(
            command = command,
            exitCode = 0,
            stdout = "(fake) ran: $command",
            durationMs = 1,
            workingDir = workingDir.path
        )
    }

    override suspend fun spawn(command: String, workingDir: File, env: Map<String, String>): Process {
        invocations += Invocation(command, workingDir, env)
        return spawnResponder(command)
    }

    override fun cancelCurrent() {
        cancelCount++
    }
}

/**
 * Deterministic in-memory [Process] for tests: emits scripted stdout/stderr and
 * either "exits" with [exitCode] after [aliveMs] or stays alive until destroyed.
 */
class FakeProcess(
    private val output: String = "",
    private val error: String = "",
    private val exitCode: Int = 0,
    private val aliveMs: Long = Long.MAX_VALUE
) : Process() {

    @Volatile
    private var destroyRequested = false

    @Volatile
    private var exit: Int? = null

    private val startedAt = System.currentTimeMillis()

    override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
    override fun getInputStream(): InputStream = ByteArrayInputStream(output.toByteArray())
    override fun getErrorStream(): InputStream = ByteArrayInputStream(error.toByteArray())

    override fun waitFor(): Int {
        while (exit == null) {
            when {
                destroyRequested -> exit = 143
                aliveMs != Long.MAX_VALUE && System.currentTimeMillis() - startedAt >= aliveMs -> exit = exitCode
                else -> Thread.sleep(5)
            }
        }
        return exit ?: 0
    }

    override fun exitValue(): Int = exit ?: throw IllegalThreadStateException("fake process still running")

    override fun destroy() {
        destroyRequested = true
    }
}
