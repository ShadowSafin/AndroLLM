package io.androllm.core.memory

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import io.androllm.core.memory.model.MemorySettings
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.memoryDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "memory_prefs"
)

/**
 * Persists [MemorySettings] in a dedicated DataStore. All values are plain
 * on-device preferences — nothing is ever sent off the device.
 */
@Singleton
class MemorySettingsStore @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private object Keys {
        val ENABLED = booleanPreferencesKey("enabled")
        val SIMILARITY_THRESHOLD = floatPreferencesKey("similarity_threshold")
        val RETRIEVAL_COUNT = intPreferencesKey("retrieval_count")
        val MAX_CONTEXT_MEMORIES = intPreferencesKey("max_context_memories")
        val MAX_CONTEXT_SUMMARIES = intPreferencesKey("max_context_summaries")
        val EXTRACTION_ENABLED = booleanPreferencesKey("extraction_enabled")
        val SUMMARIZATION_INTERVAL = intPreferencesKey("summarization_interval")
        val EMBEDDING_MODEL_PATH = stringPreferencesKey("embedding_model_path")
        val CLOUD_EMBEDDING_MODEL = stringPreferencesKey("cloud_embedding_model")
        val EMBEDDING_CONTEXT_LENGTH = intPreferencesKey("embedding_context_length")
        val EMBEDDING_BATCH_SIZE = intPreferencesKey("embedding_batch_size")
        val QUERY_PREFIX = stringPreferencesKey("query_prefix")
        val PASSAGE_PREFIX = stringPreferencesKey("passage_prefix")
        val KEEP_MODEL_LOADED = booleanPreferencesKey("keep_embedding_model_loaded")
    }

    private val dataStore: DataStore<Preferences> = context.memoryDataStore

    val settings: Flow<MemorySettings> = dataStore.data.map { prefs ->
        MemorySettings(
            enabled = prefs[Keys.ENABLED] ?: false,
            similarityThreshold = (prefs[Keys.SIMILARITY_THRESHOLD] ?: 0.78f)
                .coerceIn(MemorySettings.THRESHOLD_MIN, MemorySettings.THRESHOLD_MAX),
            retrievalCount = (prefs[Keys.RETRIEVAL_COUNT] ?: 5)
                .coerceIn(MemorySettings.RETRIEVAL_MIN, MemorySettings.RETRIEVAL_MAX),
            maxContextMemories = (prefs[Keys.MAX_CONTEXT_MEMORIES] ?: 5).coerceIn(0, 20),
            maxContextSummaries = (prefs[Keys.MAX_CONTEXT_SUMMARIES] ?: 2).coerceIn(0, 5),
            extractionEnabled = prefs[Keys.EXTRACTION_ENABLED] ?: true,
            summarizationInterval = (prefs[Keys.SUMMARIZATION_INTERVAL] ?: 20)
                .coerceIn(MemorySettings.SUMMARIZATION_MIN, MemorySettings.SUMMARIZATION_MAX),
            embeddingModelPath = prefs[Keys.EMBEDDING_MODEL_PATH] ?: "",
            cloudEmbeddingModel = prefs[Keys.CLOUD_EMBEDDING_MODEL] ?: "",
            embeddingContextLength = prefs[Keys.EMBEDDING_CONTEXT_LENGTH] ?: 512,
            embeddingBatchSize = prefs[Keys.EMBEDDING_BATCH_SIZE] ?: 512,
            queryPrefix = prefs[Keys.QUERY_PREFIX] ?: "",
            passagePrefix = prefs[Keys.PASSAGE_PREFIX] ?: "",
            keepEmbeddingModelLoaded = prefs[Keys.KEEP_MODEL_LOADED] ?: true
        )
    }

    suspend fun current(): MemorySettings = settings.first()

    suspend fun update(transform: (MemorySettings) -> MemorySettings) {
        val updated = transform(current())
        dataStore.edit { prefs ->
            prefs[Keys.ENABLED] = updated.enabled
            prefs[Keys.SIMILARITY_THRESHOLD] = updated.similarityThreshold
            prefs[Keys.RETRIEVAL_COUNT] = updated.retrievalCount
            prefs[Keys.MAX_CONTEXT_MEMORIES] = updated.maxContextMemories
            prefs[Keys.MAX_CONTEXT_SUMMARIES] = updated.maxContextSummaries
            prefs[Keys.EXTRACTION_ENABLED] = updated.extractionEnabled
            prefs[Keys.SUMMARIZATION_INTERVAL] = updated.summarizationInterval
            prefs[Keys.EMBEDDING_MODEL_PATH] = updated.embeddingModelPath
            prefs[Keys.CLOUD_EMBEDDING_MODEL] = updated.cloudEmbeddingModel
            prefs[Keys.EMBEDDING_CONTEXT_LENGTH] = updated.embeddingContextLength
            prefs[Keys.EMBEDDING_BATCH_SIZE] = updated.embeddingBatchSize
            prefs[Keys.QUERY_PREFIX] = updated.queryPrefix
            prefs[Keys.PASSAGE_PREFIX] = updated.passagePrefix
            prefs[Keys.KEEP_MODEL_LOADED] = updated.keepEmbeddingModelLoaded
        }
    }
}
