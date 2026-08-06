package io.androllm.core.memory.background

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import io.androllm.core.common.getOrThrow
import io.androllm.core.memory.MemoryManager
import io.androllm.core.memory.util.MemoryLogger
import javax.inject.Inject

/**
 * Background memory maintenance, executed by WorkManager so it NEVER blocks
 * chat generation:
 *
 * Drains the pending-embedding queue — memories stored without vectors are
 * embedded as soon as an embedding source exists (cloud provider or local
 * GGUF). The in-memory index self-heals lazily from persisted rows on the
 * next retrieval, so no re-embedding of already-indexed memories is needed.
 *
 * Extraction/summarization are NOT run here — they run right after each
 * assistant response through [MemoryManager.processExchange].
 */
@HiltWorker
class MemoryIndexingWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val memoryManager: MemoryManager,
    private val logger: MemoryLogger
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            val embedded = memoryManager.embedPendingMemories().getOrThrow()
            logger.info("Background indexing worker: embedded $embedded pending memory(s)")
            Result.success()
        } catch (e: Exception) {
            logger.warn("Background indexing failed: ${e.message}")
            Result.retry()
        }
    }
}