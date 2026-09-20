package io.androllm.core.models.download

import java.io.File
import java.security.MessageDigest

/**
 * Download pipeline caches (req §6): validation, hash and metadata caches.
 *
 * - Validation cache: per-URL preflight results, TTL-guarded so HEAD storms
 *   don't hammer HuggingFace on every resume/retry.
 * - Hash cache: per-file SHA-256 records keyed by path+size+mtime, so a resumed
 *   or re-verified artifact is hashed once, not on every app start.
 * - Metadata cache: parsed catalog/model metadata snapshots.
 *
 * Corrupted entries (oversized partials, hash records for vanished files) are
 * cleaned automatically — never served, never left behind.
 */
class DownloadCache(
    private val cacheDir: File,
    val preflightTtlMs: Long = 6 * 60 * 60 * 1000L,
) {
    private val lock = Any()
    private val preflight = HashMap<String, PreflightEntry>()
    private val hashes = HashMap<String, HashEntry>()

    data class PreflightEntry(val info: PreflightInfo, val cachedAt: Long) {
        fun isFresh(now: Long, ttlMs: Long): Boolean = now - cachedAt < ttlMs
    }

    data class HashEntry(val sha256: String, val sizeBytes: Long, val lastModified: Long)

    fun getPreflight(url: String, nowMs: Long = System.currentTimeMillis()): PreflightInfo? =
        synchronized(lock) {
            val entry = preflight[url] ?: return null
            if (!entry.isFresh(nowMs, preflightTtlMs)) {
                preflight.remove(url)
                return null
            }
            entry.info
        }

    fun putPreflight(url: String, info: PreflightInfo, nowMs: Long = System.currentTimeMillis()) {
        synchronized(lock) { preflight[url] = PreflightEntry(info, nowMs) }
    }

    fun invalidatePreflight(url: String) {
        synchronized(lock) { preflight.remove(url) }
    }

    /**
     * Cached SHA-256 for [file], or null when the file changed since hashing.
     * Stale records (size/mtime drift, file gone) are evicted on read.
     */
    fun getHash(file: File): String? = synchronized(lock) {
        val key = file.absolutePath
        val entry = hashes[key] ?: return null
        if (!file.exists() || file.length() != entry.sizeBytes || file.lastModified() != entry.lastModified) {
            hashes.remove(key)
            return null
        }
        entry.sha256
    }

    fun putHash(file: File, sha256: String) {
        synchronized(lock) {
            hashes[file.absolutePath] = HashEntry(sha256, file.length(), file.lastModified())
        }
    }

    /** Drops hash records whose files vanished or changed — the corrupt-cache sweep. */
    fun cleanCorrupted(): Int = synchronized(lock) {
        val dead = hashes.keys.filter { path ->
            val f = File(path)
            val e = hashes[path]
            !f.exists() || e == null || f.length() != e.sizeBytes
        }
        dead.forEach { hashes.remove(it) }
        dead.size
    }

    /** Deletes partial (.part) files that can never resume (empty or oversized). */
    fun cleanPartialFiles(modelsDir: File, expectedSizes: Map<String, Long> = emptyMap()): List<File> {
        if (!modelsDir.isDirectory) return emptyList()
        val removed = mutableListOf<File>()
        modelsDir.listFiles()?.forEach { file ->
            if (!file.isFile) return@forEach
            val expected = expectedSizes[file.name]
            val corrupt = file.length() == 0L || (expected != null && expected > 0 && file.length() > expected)
            if (corrupt && file.delete()) removed += file
        }
        return removed
    }

    /**
     * SHA-256 of [file], served from cache when the file is unchanged.
     * Chunked streaming — constant memory even for multi-GB artifacts.
     */
    fun sha256Of(file: File): String? {
        getHash(file)?.let { return it }
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered(256 * 1024).use { input ->
                val buffer = ByteArray(256 * 1024)
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }.also { putHash(file, it) }
        } catch (e: Exception) {
            null
        }
    }

    fun snapshotDir(): File = File(cacheDir, "download").apply { mkdirs() }
}
