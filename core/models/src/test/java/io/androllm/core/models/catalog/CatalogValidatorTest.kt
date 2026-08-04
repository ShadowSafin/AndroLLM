package io.androllm.core.models.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogValidatorTest {

    private fun model(
        id: String = "m1",
        architecture: String = "llama",
        quantization: String = "Q4_K_M",
        sha256: String? = null,
        downloadUrl: String = "https://huggingface.co/t/m-gguf/resolve/main/m-q4_k_m.gguf",
        sizeBytes: Long = 1_000_000_000,
        contextLength: Int = 8192,
        license: String = "Apache-2.0",
        categories: List<String> = listOf("CHAT")
    ) = CatalogModel(
        id = id,
        name = "M $id",
        family = "Test",
        architecture = architecture,
        categories = categories,
        license = license,
        author = "T",
        repoId = "t/m-gguf",
        fileName = "m-q4_k_m.gguf",
        downloadUrl = downloadUrl,
        sizeBytes = sizeBytes,
        parameters = "1.5B",
        quantization = quantization,
        contextLength = contextLength,
        sha256 = sha256
    )

    @Test
    fun validModelPasses() {
        val report = CatalogValidator.validate(listOf(model(sha256 = "a".repeat(64))))
        assertTrue(report.errors.joinToString(), report.errors.isEmpty())
    }

    @Test
    fun duplicateIdsAreErrors() {
        val report = CatalogValidator.validate(listOf(model("dup"), model("dup")))
        assertTrue(report.errors.any { it.contains("Duplicate") })
    }

    @Test
    fun unsupportedArchitectureIsAnError() {
        val report = CatalogValidator.validate(listOf(model(architecture = "madeup")))
        assertTrue(report.errors.any { it.contains("not supported") })
    }

    @Test
    fun badSha256IsAnError() {
        val report = CatalogValidator.validate(listOf(model(sha256 = "not-a-sha")))
        assertTrue(report.errors.any { it.contains("sha256") })
    }

    @Test
    fun nonHttpsUrlIsAnError() {
        val report = CatalogValidator.validate(listOf(model(downloadUrl = "http://insecure.example/m.gguf")))
        assertTrue(report.errors.any { it.contains("https") })
    }

    @Test
    fun unknownQuantizationIsOnlyAWarning() {
        val report = CatalogValidator.validate(listOf(model(quantization = "IQ5_0")))
        assertTrue(report.errors.isEmpty())
        assertTrue(report.warnings.any { it.contains("IQ5_0") })
    }

    @Test
    fun missingPopularityMetricsWarnNotFail() {
        val report = CatalogValidator.validate(listOf(model()))
        assertTrue(report.isValid)
    }

    @Test
    fun negativeLikesIsAnError() {
        val bad = model().copy(likes = -1)
        val report = CatalogValidator.validate(listOf(bad))
        assertFalse(report.isValid)
    }
}
