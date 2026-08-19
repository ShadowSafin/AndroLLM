package io.androllm.feature.models.downloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelDownloadWorkerLogicTest {

    // ---- isValidDownloadUrl -------------------------------------------------

    @Test
    fun `valid https url is accepted`() {
        assertTrue(isValidDownloadUrl("https://huggingface.co/litert-community/Qwen3-0.6B/resolve/main/qwen3_0_6b_mixed_int4.litertlm"))
    }

    @Test
    fun `valid http url is accepted`() {
        assertTrue(isValidDownloadUrl("http://example.com/model.litertlm"))
    }

    @Test
    fun `blank url is rejected`() {
        assertFalse(isValidDownloadUrl(""))
    }

    @Test
    fun `non-http scheme is rejected`() {
        assertFalse(isValidDownloadUrl("file:///sdcard/model.litertlm"))
        assertFalse(isValidDownloadUrl("ftp://example.com/model.litertlm"))
        assertFalse(isValidDownloadUrl("content://media/model.litertlm"))
    }

    @Test
    fun `malformed url is rejected`() {
        assertFalse(isValidDownloadUrl("not a url at all"))
        assertFalse(isValidDownloadUrl("https://"))
    }

    // ---- resolveTotalBytes --------------------------------------------------

    @Test
    fun `fresh download uses content-length directly`() {
        assertEquals(497664000L, resolveTotalBytes(497664000L, isPartial = false, downloadedBytes = 0L, expectedSize = 0L))
    }

    @Test
    fun `resumed download adds resume offset to remaining content-length`() {
        assertEquals(1_000_000L, resolveTotalBytes(750_000L, isPartial = true, downloadedBytes = 250_000L, expectedSize = 0L))
    }

    @Test
    fun `missing content-length falls back to catalog size`() {
        assertEquals(497_664_000L, resolveTotalBytes(-1L, isPartial = false, downloadedBytes = 0L, expectedSize = 497_664_000L))
    }

    @Test
    fun `chunked transfer with no catalog size is unknown`() {
        assertEquals(-1L, resolveTotalBytes(-1L, isPartial = false, downloadedBytes = 0L, expectedSize = 0L))
    }

    @Test
    fun `partial response with unknown remaining is unknown`() {
        assertEquals(-1L, resolveTotalBytes(-1L, isPartial = true, downloadedBytes = 100L, expectedSize = 0L))
    }

    // ---- isHttpFailurePermanent ---------------------------------------------

    @Test
    fun `client errors are permanent except timeout and rate-limit`() {
        assertTrue(isHttpFailurePermanent(400))
        assertTrue(isHttpFailurePermanent(401))
        assertTrue(isHttpFailurePermanent(403))
        assertTrue(isHttpFailurePermanent(404))
        assertTrue(isHttpFailurePermanent(416))
        assertFalse(isHttpFailurePermanent(408))
        assertFalse(isHttpFailurePermanent(429))
    }

    @Test
    fun `server errors are transient`() {
        assertFalse(isHttpFailurePermanent(500))
        assertFalse(isHttpFailurePermanent(502))
        assertFalse(isHttpFailurePermanent(503))
    }

    @Test
    fun `success and redirect codes are not permanent`() {
        assertFalse(isHttpFailurePermanent(200))
        assertFalse(isHttpFailurePermanent(206))
        assertFalse(isHttpFailurePermanent(301))
        assertFalse(isHttpFailurePermanent(307))
    }
}
