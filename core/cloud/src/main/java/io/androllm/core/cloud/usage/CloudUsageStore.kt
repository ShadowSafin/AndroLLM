package io.androllm.core.cloud.usage

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import timber.log.Timber

/**
 * Persistence boundary for cloud usage data — implemented by
 * [FileCloudUsageStore] in production and by an in-memory fake in tests.
 *
 * The store owns ONE invariant: a corrupted or partially-written file must
 * never crash the cloud experience. Implementations decode defensively and
 * fall back to an empty state.
 */
interface CloudUsageStore {
    /** Loads the persisted state; empty state on first run or corruption. */
    suspend fun load(): CloudUsageState

    /** Replaces the persisted state atomically. */
    suspend fun save(state: CloudUsageState)

    /** Drops all persisted usage data (dashboard "clear" action). */
    suspend fun clear()
}

/** Test/development store that keeps everything in memory. */
class InMemoryCloudUsageStore : CloudUsageStore {
    private var state = CloudUsageState()
    private val mutex = Mutex()

    override suspend fun load(): CloudUsageState = mutex.withLock { state }

    override suspend fun save(state: CloudUsageState) = mutex.withLock { this.state = state }

    override suspend fun clear() = mutex.withLock { state = CloudUsageState() }
}

/**
 * JSON-file backed usage store.
 *
 * Durability strategy: writes go to a temp file first, then replace the
 * target via atomic rename — a crash mid-write can never leave a torn file
 * in place. Decoding is fully defensive: any parse failure logs and yields
 * an empty state (usage counters then simply restart from zero; the cloud
 * pipeline itself is unaffected).
 */
class FileCloudUsageStore(
    private val file: File
) : CloudUsageStore {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }
    private val mutex = Mutex()

    override suspend fun load(): CloudUsageState = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!file.exists() || file.length() == 0L) return@withContext CloudUsageState()
            runCatching {
                json.decodeFromString(CloudUsageState.serializer(), file.readText(Charsets.UTF_8))
            }.getOrElse { e ->
                // Corruption must never propagate — quarantine the bad file
                // once so we don't log on every load, then start fresh.
                Timber.w(e, "CloudUsageStore: state file unreadable — starting fresh")
                runCatching { file.renameTo(File(file.parentFile, file.name + ".corrupt")) }
                CloudUsageState()
            }
        }
    }

    override suspend fun save(state: CloudUsageState): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching {
                val parent = file.parentFile
                if (parent != null && !parent.exists()) parent.mkdirs()
                val tmp = File(file.parentFile, file.name + ".tmp")
                tmp.writeText(json.encodeToString(CloudUsageState.serializer(), state), Charsets.UTF_8)
                // Atomic replace: rename overwrites the target on POSIX and
                // on Android's ext4/f2fs filesystems.
                if (!tmp.renameTo(file)) {
                    // Fallback for filesystems without atomic rename-over.
                    file.writeText(tmp.readText(Charsets.UTF_8), Charsets.UTF_8)
                    tmp.delete()
                }
            }.onFailure { e ->
                // Usage persistence is best-effort: a failed write must never
                // take down request handling.
                Timber.w(e, "CloudUsageStore: failed to persist usage state")
            }
        }
    }

    override suspend fun clear(): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching { if (file.exists()) file.delete() }
        }
    }
}
