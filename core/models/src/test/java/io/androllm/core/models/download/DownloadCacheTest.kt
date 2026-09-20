package io.androllm.core.models.download

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DownloadCacheTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `preflight entries expire by ttl`() {
        val cache = DownloadCache(tmp.newFolder(), preflightTtlMs = 1000L)
        val info = PreflightInfo(url = "https://x", contentLength = 10L)
        cache.putPreflight("https://x", info, nowMs = 0L)
        assertThat(cache.getPreflight("https://x", nowMs = 500L)).isEqualTo(info)
        assertThat(cache.getPreflight("https://x", nowMs = 2000L)).isNull()
    }

    @Test
    fun `stale hash records are evicted on read`() {
        val cache = DownloadCache(tmp.newFolder())
        val file = tmp.newFile("m.litertlm").apply { writeBytes(ByteArray(64)) }
        val sha = cache.sha256Of(file)
        assertThat(sha).isNotNull()
        assertThat(cache.getHash(file)).isEqualTo(sha)
        file.appendBytes(ByteArray(8))
        assertThat(cache.getHash(file)).isNull()
    }

    @Test
    fun `cleanCorrupted drops records for vanished files`() {
        val cache = DownloadCache(tmp.newFolder())
        val file = tmp.newFile("gone.litertlm").apply { writeBytes(ByteArray(16)) }
        cache.sha256Of(file)
        assertThat(file.delete()).isTrue()
        assertThat(cache.cleanCorrupted()).isEqualTo(1)
    }

    @Test
    fun `cleanPartialFiles removes empty partials`() {
        val cache = DownloadCache(tmp.newFolder())
        val dir = tmp.newFolder("models")
        val empty = java.io.File(dir, "a.part").apply { createNewFile() }
        val good = java.io.File(dir, "b.part").apply { writeBytes(ByteArray(16)) }
        val removed = cache.cleanPartialFiles(dir)
        assertThat(removed.map { it.name }).containsExactly("a.part")
        assertThat(empty.exists()).isFalse()
        assertThat(good.exists()).isTrue()
    }
}
