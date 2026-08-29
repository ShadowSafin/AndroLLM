package io.androllm.feature.coding.task

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Persistence boundary for the live task state. Production writes a JSON
 * file per workspace under the app's state root; unit tests use a temp dir.
 * All write paths are mutex-serialised so concurrent saves never produce a
 * torn file.
 */
interface TaskStateRepository {
    suspend fun save(state: CodingTaskState)
    suspend fun load(workspaceId: String): CodingTaskState?
    suspend fun clear(workspaceId: String)
}

/**
 * File-backed [TaskStateRepository]. One file per workspace. Atomic saves
 * (write to a temp file then rename) so a crash mid-save never produces a
 * half-written JSON.
 */
class FileTaskStateRepository(private val root: File) : TaskStateRepository {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
        encodeDefaults = true
    }
    private val mutexes = java.util.concurrent.ConcurrentHashMap<String, Mutex>()

    private fun mutexFor(workspaceId: String): Mutex = mutexes.getOrPut(workspaceId) { Mutex() }
    private fun fileFor(workspaceId: String): File {
        val safe = workspaceId.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return File(root, "$safe.task.json")
    }

    override suspend fun save(state: CodingTaskState) = withContext(Dispatchers.IO) {
        root.mkdirs()
        val mut = mutexFor(state.workspaceId)
        mut.withLock {
            val next = state.copy(lastUpdatedMs = System.currentTimeMillis(), version = state.version + 1)
            val tmp = File(root, "${state.workspaceId}.task.json.tmp")
            tmp.writeText(json.encodeToString(CodingTaskState.serializer(), next))
            // Atomic-ish rename: on most filesystems this is atomic.
            if (!tmp.renameTo(fileFor(state.workspaceId))) {
                fileFor(state.workspaceId).writeBytes(tmp.readBytes())
                tmp.delete()
            }
        }
        Unit
    }

    override suspend fun load(workspaceId: String): CodingTaskState? = withContext(Dispatchers.IO) {
        val file = fileFor(workspaceId)
        if (!file.exists()) return@withContext null
        runCatching { json.decodeFromString<CodingTaskState>(file.readText()) }.getOrNull()
    }

    override suspend fun clear(workspaceId: String) = withContext(Dispatchers.IO) {
        mutexFor(workspaceId).withLock {
            runCatching { fileFor(workspaceId).delete() }
        }
        Unit
    }
}
