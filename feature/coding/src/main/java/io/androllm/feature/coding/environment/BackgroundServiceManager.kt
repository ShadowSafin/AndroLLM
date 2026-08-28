package io.androllm.feature.coding.environment

import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

/** UI/model-safe snapshot of one running (or exited) background service. */
data class BackgroundServiceInfo(
    val id: String,
    val command: String,
    val running: Boolean,
    val statusLabel: String,
    val port: Int?,
    val urlOnDevice: String?,
    val urlNetwork: String?,
    val startedAtMs: Long,
    val logFile: String
)

/** Outcome of attempting to start a background service. */
sealed interface BackgroundStartOutcome {
    data class Started(val summary: String, val service: BackgroundServiceInfo) : BackgroundStartOutcome
    data class Failed(
        val summary: String,
        /** Set when the failure is a missing runtime addon (drives auto-install). */
        val missingAddonId: String? = null
    ) : BackgroundStartOutcome
}

/**
 * Detects the port a dev server announces in its startup output.
 *
 * Covers the common shapes: Vite/Next/Nuxt "Local: http://localhost:5173/",
 * Node "listening on port 3000", Flask "Running on http://127.0.0.1:5000",
 * and bare "host:PORT" announcements. Pure + deterministic for unit tests.
 */
object ServerPortDetector {

    private val URL_PORT = Regex(
        """https?://(?:localhost|127\.0\.0\.1|0\.0\.0\.0|\[?::1?\]?|(?:\d{1,3}\.){3}\d{1,3}):(\d{2,5})"""
    )
    private val LISTEN_PATTERNS = listOf(
        Regex("""(?i)\b(?:listening on|running at|running on|available on|server started at|serving on)\b[^\d\n]{0,40}?:(\d{2,5})"""),
        Regex("""(?i)\bport\s+(\d{2,5})\b""")
    )
    private val BARE_PORT = Regex("""(?<![\d./]):(\d{4,5})(?!\d)""")

    /** Returns the first plausible server port found in [text], or null. */
    fun detect(text: String): Int? {
        if (text.isBlank()) return null
        URL_PORT.find(text)?.let { portOf(it.groupValues[1])?.let { p -> return p } }
        for (pattern in LISTEN_PATTERNS) {
            pattern.find(text)?.let { portOf(it.groupValues[1])?.let { p -> return p } }
        }
        BARE_PORT.find(text)?.let { portOf(it.groupValues[1], requireFourDigits = true)?.let { p -> return p } }
        return null
    }

    private fun portOf(raw: String, requireFourDigits: Boolean = false): Int? {
        if (requireFourDigits && raw.length < 4) return null
        val port = raw.toIntOrNull() ?: return null
        // Accept common server ports; reject line-noise like :0 or :99999.
        return if (port in 1024..65535) port else null
    }
}

/**
 * Runs long-lived commands (dev servers, watchers, REPLs) as **background
 * services** that outlive the tool call that started them.
 *
 * The command is spawned detached through the attached [ShellBackend] (proot
 * when the Linux base is provisioned). Output is drained continuously into a
 * bounded in-memory buffer + a log file under [servicesDir]; the manager scans
 * the output for an announced port and reports access URLs:
 *
 *  - `http://localhost:<port>` — reachable on the device itself, and
 *  - `http://<device-ip>:<port>` — reachable from other devices on the same
 *    network (proot shares the host network stack, so a guest server bound to
 *    0.0.0.0 is genuinely reachable over Wi-Fi).
 *
 * Services live as long as the app process lives (they are children of it);
 * [stop] / [stopAll] destroy them explicitly.
 */
class BackgroundServiceManager(
    private val servicesDir: File,
    private val portWaitMs: Long = DEFAULT_PORT_WAIT_MS,
    private val deviceIpProvider: () -> String? = ::deviceIpv4,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {

    private class ServiceEntry(
        val id: String,
        val command: String,
        val workingDirPath: String,
        val process: Process,
        val logFile: File,
        val startedAtMs: Long
    ) {
        val lines = ArrayDeque<String>()
        var port: Int? = null
        var exitCode: Int? = null
        var stoppedByUser = false

        val isAlive: Boolean get() = exitCode == null

        @Synchronized
        fun appendLine(line: String) {
            lines.addLast(line)
            while (lines.size > MAX_LINES) lines.removeFirst()
            if (port == null) port = ServerPortDetector.detect(line)
            runCatching {
                logFile.appendText(line + "\n")
            }
        }

        @Synchronized
        fun tail(chars: Int): String {
            val all = lines.joinToString("\n")
            return if (all.length <= chars) all else "…[earlier output trimmed]\n" + all.takeLast(chars)
        }
    }

    private val entries = CopyOnWriteArrayList<ServiceEntry>()

    private val _state = MutableStateFlow<List<BackgroundServiceInfo>>(emptyList())
    /** Live snapshot list for the UI (running services strip). */
    val state: StateFlow<List<BackgroundServiceInfo>> = _state.asStateFlow()

    @Volatile
    private var backend: ShellBackend? = null

    /** Wires (or re-wires) the shell backend used to spawn services. */
    fun attachBackend(shellBackend: ShellBackend) {
        backend = shellBackend
    }

    /**
     * Spawns [command] detached in [workingDir] and waits up to [portWaitMs] for
     * the server to announce a port (or to crash early). Never throws.
     */
    suspend fun start(
        command: String,
        workingDir: File,
        env: Map<String, String> = emptyMap()
    ): BackgroundStartOutcome {
        val activeBackend = backend
            ?: return BackgroundStartOutcome.Failed("No shell backend is attached — open a workspace first.")

        val id = "svc-" + UUID.randomUUID().toString().substring(0, 6)
        servicesDir.mkdirs()
        val logFile = File(servicesDir, "$id.log")
        runCatching { if (logFile.exists()) logFile.delete() }

        val process = runCatching { activeBackend.spawn(command, workingDir, env) }.getOrElse {
            Timber.e(it, "background spawn failed: %s", command)
            return BackgroundStartOutcome.Failed("Failed to start '$command': ${it.message}")
        }

        val entry = ServiceEntry(
            id = id,
            command = command,
            workingDirPath = workingDir.path,
            process = process,
            logFile = logFile,
            startedAtMs = System.currentTimeMillis()
        )
        entries += entry
        pump(entry)
        publish()
        Timber.i("background service started: id=%s cmd=%s", id, command)

        // Give the server a window to print its port (or crash early).
        val deadline = System.currentTimeMillis() + portWaitMs
        while (System.currentTimeMillis() < deadline) {
            if (!entry.isAlive || entry.port != null) break
            delay(POLL_MS)
        }

        // Crashed during startup → report as a failure with the output so far.
        if (!entry.isAlive) {
            val code = entry.exitCode ?: 0
            entries -= entry
            publish()
            val tail = entry.tail(2000)
            return BackgroundStartOutcome.Failed(
                "The command exited immediately (exit $code) — it is NOT running.\n" +
                    "Output:\n$tail\n" +
                    "If this was meant to be a long-running server, check that the script/target exists " +
                    "and that dependencies are installed, then retry."
            )
        }

        val info = snapshot(entry)
        return BackgroundStartOutcome.Started(buildStartedSummary(entry, info), info)
    }

    /** Live services (plus recently exited ones, for status reporting). */
    fun list(): List<BackgroundServiceInfo> = entries.map { snapshot(it) }

    /** Tail of a service's captured output (null when the id is unknown). */
    fun logTail(id: String, chars: Int = 4000): String? =
        entries.firstOrNull { it.id == id }?.tail(chars)

    /** Stops a service. Returns a human/model-readable summary. */
    fun stop(id: String): String {
        val entry = entries.firstOrNull { it.id == id }
            ?: return "No background service with id '$id'. Use list_background_services to see ids."
        entry.stoppedByUser = true
        entry.process.destroyForcibly()
        // Give the process a moment to actually die so the status is accurate.
        val deadline = System.currentTimeMillis() + 2000
        while (entry.isAlive && System.currentTimeMillis() < deadline) {
            Thread.sleep(25)
        }
        if (entry.isAlive) entry.exitCode = 143
        entries -= entry
        publish()
        Timber.i("background service stopped: id=%s cmd=%s", id, entry.command)
        return "Stopped service '$id' (${entry.command})."
    }

    /** Stops every running service (workspace teardown / "stop all"). */
    fun stopAll() {
        entries.toList().forEach { stop(it.id) }
    }

    // ── internals ────────────────────────────────────────────────────────────

    private fun pump(entry: ServiceEntry) {
        scope.launch {
            runCatching {
                LocalShellBackend.pumpLines(entry.process.inputStream, StringBuilder()) { entry.appendLine(it) }
            }
        }
        scope.launch {
            runCatching {
                LocalShellBackend.pumpLines(entry.process.errorStream, StringBuilder()) { entry.appendLine(it) }
            }
        }
        scope.launch {
            val code = runCatching { entry.process.waitFor() }.getOrDefault(143)
            if (entry.exitCode == null) entry.exitCode = code
            Timber.i("background service exited: id=%s exit=%d", entry.id, code)
            publish()
        }
        // Publish once shortly after start in case a port appears right away
        // (the pump coroutines do not trigger publishes per line).
        scope.launch {
            delay(POLL_MS)
            publish()
        }
    }

    private fun publish() {
        _state.value = entries.map { snapshot(it) }
    }

    private fun snapshot(entry: ServiceEntry): BackgroundServiceInfo {
        val port = entry.port
        val ip = runCatching { deviceIpProvider() }.getOrNull()
        val running = entry.isAlive && entry.process.isAlive
        val status = when {
            running -> "RUNNING"
            entry.stoppedByUser -> "STOPPED"
            else -> "EXITED (${entry.exitCode ?: "?"})"
        }
        return BackgroundServiceInfo(
            id = entry.id,
            command = entry.command,
            running = running,
            statusLabel = status,
            port = port,
            urlOnDevice = port?.let { "http://localhost:$it" },
            urlNetwork = if (port != null && ip != null) "http://$ip:$port" else null,
            startedAtMs = entry.startedAtMs,
            logFile = entry.logFile.absolutePath
        )
    }

    private fun buildStartedSummary(entry: ServiceEntry, info: BackgroundServiceInfo): String = buildString {
        append("✅ Running in background as service ").append(entry.id).append(".\n")
        append("command: ").append(entry.command).append('\n')
        append("status: ").append(info.statusLabel).append('\n')
        val port = info.port
        if (port != null) {
            append("port: ").append(port).append('\n')
            append("On-device URL: ").append(info.urlOnDevice).append('\n')
            if (info.urlNetwork != null) {
                append("Network URL: ").append(info.urlNetwork)
                    .append("  (reachable from other devices on the same Wi-Fi)\n")
            }
        } else {
            append("No port detected yet — the server may still be starting, or it may not listen on a port.\n")
        }
        append("Startup output so far:\n").append(entry.tail(1500)).append('\n')
        append("The service keeps running independently of this tool call. ")
        append("Check it with list_background_services; stop it with stop_background_service id=\"${entry.id}\".")
    }

    companion object {
        const val DEFAULT_PORT_WAIT_MS = 20_000L
        private const val POLL_MS = 250L
        private const val MAX_LINES = 1000

        /**
         * The device's current LAN IPv4 address (wlan preferred), or null. No
         * permissions needed — reads the app's own network interfaces.
         */
        fun deviceIpv4(): String? = runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .asSequence()
                .filter { nic -> runCatching { nic.isUp && !nic.isLoopback }.getOrDefault(false) }
                .sortedBy { if (it.name.startsWith("wlan")) 0 else 1 }
                .flatMap { nic -> nic.interfaceAddresses.mapNotNull { it.address as? Inet4Address } }
                .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }
                .firstOrNull()
                ?.hostAddress
        }.getOrNull()
    }
}
