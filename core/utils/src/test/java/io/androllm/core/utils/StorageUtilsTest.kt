package io.androllm.core.utils

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for storage helpers that do not require an Android context.
 */
class StorageUtilsTest {

    @Test
    fun `formatBytes handles all units`() {
        assertEquals("512 B", StorageUtils.formatBytes(512))
        assertEquals("1.5 KB", StorageUtils.formatBytes(1536))
        assertEquals("2.0 MB", StorageUtils.formatBytes(2L * 1024 * 1024))
        assertEquals("1.0 GB", StorageUtils.formatBytes(1024L * 1024 * 1024))
    }

    @Test
    fun `calculateDirectorySize sums file sizes`() {
        val dir = File.createTempFile("storage-utils-test", "").let { file ->
            file.delete()
            File(file.parentFile, "storage-utils-dir")
        }
        dir.mkdirs()
        try {
            File(dir, "a.txt").writeBytes(ByteArray(100))
            File(dir, "b.txt").writeBytes(ByteArray(200))
            assertEquals(300, StorageUtils.calculateDirectorySize(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `calculateDirectorySize of missing dir is zero`() {
        assertEquals(0, StorageUtils.calculateDirectorySize(File("does-not-exist")))
    }

    @Test
    fun `device info is populated`() {
        assertTrue(DeviceUtils.getCpuCoreCount() > 0)
        assertTrue(DeviceUtils.getDeviceModel().isNotBlank())
    }

    @Test
    fun `storage stats report the real free space, not total minus models`() {
        // Old bug: availableBytes was (total - modelsUsed), ignoring all other
        // data on the filesystem. Now freeBytes is the source of truth.
        val stats = StorageStats(totalBytes = 128L * 1024 * 1024 * 1024, usedBytes = 4L * 1024 * 1024 * 1024, freeBytes = 2L * 1024 * 1024 * 1024)
        assertEquals(2L * 1024 * 1024 * 1024, stats.availableBytes)
        assertEquals(2L * 1024 * 1024 * 1024, stats.freeBytes)
    }

    @Test
    fun `storage stats fall back gracefully when free space is unknown`() {
        // freeBytes == 0 (e.g. pre-fix data): fall back to total - used, clamped.
        val stats = StorageStats(totalBytes = 100L, usedBytes = 30L)
        assertEquals(70L, stats.availableBytes)
        assertEquals(0f, StorageStats(totalBytes = 100L, usedBytes = 200L).availableBytes.toFloat())
    }

    @Test
    fun `free fraction drives the model storage usage bar`() {
        val stats = StorageStats(totalBytes = 100L, usedBytes = 10L, freeBytes = 25L)
        assertEquals(0.25f, stats.freeFraction)
        assertEquals(0f, StorageStats(totalBytes = 0L, usedBytes = 0L).freeFraction)
    }
}
