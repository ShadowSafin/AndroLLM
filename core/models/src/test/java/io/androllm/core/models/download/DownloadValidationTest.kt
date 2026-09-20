package io.androllm.core.models.download

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DownloadValidationTest {

    @Test
    fun `failure report shows expected actual difference reason and action`() {
        val failure = ValidationFailure(
            reason = FailureReason.DOWNLOAD_INTERRUPTED,
            expectedBytes = 497_664_000L,
            actualBytes = 497_512_908L,
            detail = "Download interrupted.",
            suggestedAction = "Resume download.",
            repairAction = RepairAction.RESUME_MISSING_BYTES,
        )
        val report = failure.formatReport("Qwen3 0.6B")
        assertThat(report).contains("❌ Validation Failed")
        assertThat(report).contains("497,664,000")
        assertThat(report).contains("497,512,908")
        assertThat(report).contains("151,092")
        assertThat(report).contains("Download interrupted.")
        assertThat(report).contains("Resume download.")
    }

    @Test
    fun `byte difference is expected minus actual`() {
        val truncated = ValidationFailure(
            reason = FailureReason.TRUNCATED_TRANSFER,
            expectedBytes = 1000L,
            actualBytes = 600L,
        )
        assertThat(truncated.byteDifference).isEqualTo(400L)
        val oversized = truncated.copy(actualBytes = 1200L)
        assertThat(oversized.byteDifference).isEqualTo(-200L)
    }

    @Test
    fun `preflight recovers sha256 from lfs tag`() {
        val sha = "a".repeat(64)
        val info = PreflightInfo(url = "https://x", lfsSha256 = sha)
        assertThat(info.recoveredSha256()).isEqualTo(sha)
    }

    @Test
    fun `preflight prefers explicit etag over lfs tag`() {
        val info = PreflightInfo(
            url = "https://x",
            eTag = "\"" + "b".repeat(64) + "\"",
            lfsSha256 = "a".repeat(64),
        )
        assertThat(info.recoveredSha256()).isEqualTo("b".repeat(64))
    }

    @Test
    fun `preflight ignores non-hash etags`() {
        val info = PreflightInfo(url = "https://x", eTag = "\"abc123\"")
        assertThat(info.recoveredSha256()).isNull()
    }

    @Test
    fun `authoritative size is null when server sends no length`() {
        assertThat(PreflightInfo(url = "https://x").authoritativeSize).isNull()
        assertThat(PreflightInfo(url = "https://x", contentLength = 10L).authoritativeSize).isEqualTo(10L)
    }

    @Test
    fun `backend parser accepts legacy gpu alias`() {
        assertThat(ModelCompatibility.parseBackend("GPU")).isEqualTo("VULKAN")
        assertThat(ModelCompatibility.parseBackend("cpu")).isEqualTo("CPU")
        assertThat(ModelCompatibility.parseBackend("QNN")).isNull()
    }
}
