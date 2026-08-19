package io.androllm.core.models.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogValidatorTest {

    private fun model(
        id: String = "m1",
        family: String = "Qwen",
        architecture: String = "qwen3",
        quantization: String = "Q4_K_M",
        sha256: String? = null,
        downloadUrl: String = "https://huggingface.co/t/m-litertlm/resolve/main/m-q4_k_m.litertlm",
        sizeBytes: Long = 1_000_000_000,
        contextLength: Int = 8192,
        license: String = "Apache-2.0",
        categories: List<String> = listOf("CHAT"),
        fileName: String = "m-q4_k_m.litertlm",
        runtimeFormat: String = "LITERTLM",
        fileFormat: String = "LITERTLM",
        mimeType: String = "application/x-litertlm",
        containerType: String? = "qwen3",
        version: String = "1.0.0"
    ) = CatalogModel(
        id = id,
        name = "M $id",
        family = family,
        architecture = architecture,
        categories = categories,
        license = license,
        author = "T",
        repoId = "t/m-litertlm",
        fileName = fileName,
        downloadUrl = downloadUrl,
        sizeBytes = sizeBytes,
        parameters = "1.5B",
        quantization = quantization,
        contextLength = contextLength,
        sha256 = sha256,
        runtimeFormat = runtimeFormat,
        fileFormat = fileFormat,
        mimeType = mimeType,
        containerType = containerType,
        version = version
    )

    private fun tfliteModel(
        id: String = "e1",
        family: String = "Gemma",
        architecture: String = "gemma-embedding",
        categories: List<String> = listOf("EMBEDDING")
    ) = model(
        id = id,
        family = family,
        architecture = architecture,
        categories = categories,
        fileName = "m-embed.tflite",
        downloadUrl = "https://huggingface.co/t/m-embed/resolve/main/m-embed.tflite",
        runtimeFormat = "TFLITE",
        fileFormat = "TFLITE",
        mimeType = "application/x-tflite",
        containerType = null
    )

    @Test
    fun validModelPasses() {
        val report = CatalogValidator.validate(listOf(model(sha256 = "a".repeat(64))))
        assertTrue(report.errors.joinToString(), report.errors.isEmpty())
    }

    @Test
    fun validTfliteModelPasses() {
        val report = CatalogValidator.validate(listOf(tfliteModel()))
        assertTrue(report.errors.joinToString(), report.errors.isEmpty())
    }

    @Test
    fun duplicateIdsAreErrors() {
        val report = CatalogValidator.validate(listOf(model("dup"), model("dup")))
        assertTrue(report.errors.any { it.contains("Duplicate") })
    }

    @Test
    fun unknownFamilyIsAnError() {
        val report = CatalogValidator.validate(listOf(model(family = "Test")))
        assertTrue(report.errors.any { it.contains("not in the model metadata registry") })
    }

    @Test
    fun unsupportedArchitectureIsAnError() {
        val report = CatalogValidator.validate(listOf(model(architecture = "madeup")))
        assertTrue(report.errors.any { it.contains("does not belong to family") })
    }

    @Test
    fun architectureFromAnotherFamilyIsAnError() {
        // "whisper" is a registered architecture — but not under family "Qwen".
        val report = CatalogValidator.validate(listOf(model(architecture = "whisper")))
        assertTrue(report.errors.any { it.contains("does not belong to family") })
    }

    @Test
    fun unknownRuntimeFormatIsAnError() {
        val report = CatalogValidator.validate(listOf(model(runtimeFormat = "GGUF")))
        assertTrue(report.errors.any { it.contains("not a known container format") })
    }

    @Test
    fun formatOutsideFamilyIsAnError() {
        // Whisper is a TFLITE-only family; a LITERTLM whisper entry is invalid.
        val report = CatalogValidator.validate(
            listOf(
                model(
                    family = "Whisper",
                    architecture = "whisper",
                    categories = listOf("AUDIO"),
                    containerType = "generic_model",
                    fileName = "m.litertlm",
                    downloadUrl = "https://huggingface.co/t/m-litertlm/resolve/main/m.litertlm"
                )
            )
        )
        assertTrue(report.errors.any { it.contains("does not support format") })
    }

    @Test
    fun badSha256IsAnError() {
        val report = CatalogValidator.validate(listOf(model(sha256 = "not-a-sha")))
        assertTrue(report.errors.any { it.contains("sha256") })
    }

    @Test
    fun nonHttpsUrlIsAnError() {
        val report = CatalogValidator.validate(listOf(model(downloadUrl = "http://insecure.example/m.litertlm")))
        assertTrue(report.errors.any { it.contains("https") })
    }

    @Test
    fun ggufFileNameIsAnError() {
        // A GGUF artifact (pre-LiteRT schema) must fail validation so a stale
        // remote catalog cannot replace the bundled LiteRT catalog with a
        // 101-model GGUF list the runtime cannot load.
        val report = CatalogValidator.validate(listOf(model(fileName = "m-q4_k_m.gguf")))
        assertTrue(report.errors.any { it.contains("not a LiteRT artifact") })
        assertFalse(report.isValid)
    }

    @Test
    fun missingVersionIsAnError() {
        val report = CatalogValidator.validate(listOf(model(version = "")))
        assertTrue(report.errors.any { it.contains("'version' is required") })
    }

    @Test
    fun missingFileFormatIsAnError() {
        val report = CatalogValidator.validate(listOf(model(fileFormat = "")))
        assertTrue(report.errors.any { it.contains("'fileFormat' is required") })
    }

    @Test
    fun missingMimeTypeIsAnError() {
        val report = CatalogValidator.validate(listOf(model(mimeType = "")))
        assertTrue(report.errors.any { it.contains("'mimeType' is required") })
    }

    @Test
    fun mimeMismatchIsAnError() {
        val report = CatalogValidator.validate(listOf(model(mimeType = "application/x-tflite")))
        assertTrue(report.errors.any { it.contains("does not match fileFormat") })
    }

    @Test
    fun fileFormatRuntimeFormatMismatchIsAnError() {
        val report = CatalogValidator.validate(listOf(model(runtimeFormat = "TFLITE")))
        assertTrue(report.errors.any { it.contains("does not match runtimeFormat") })
    }

    @Test
    fun missingContainerTypeIsAnError() {
        val report = CatalogValidator.validate(listOf(model(containerType = null)))
        assertTrue(report.errors.any { it.contains("containerType is required") })
    }

    @Test
    fun unknownContainerTypeIsAnError() {
        val report = CatalogValidator.validate(listOf(model(containerType = "llama4")))
        assertTrue(report.errors.any { it.contains("not a registered LlmModelType") })
    }

    @Test
    fun tfliteWithContainerTypeIsAnError() {
        val report = CatalogValidator.validate(listOf(tfliteModel().copy(containerType = "qwen3")))
        assertTrue(report.errors.any { it.contains("must be empty for .tflite") })
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