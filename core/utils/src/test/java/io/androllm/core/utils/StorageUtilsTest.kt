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
}
