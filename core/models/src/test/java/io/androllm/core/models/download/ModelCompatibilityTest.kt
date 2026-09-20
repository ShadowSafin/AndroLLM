package io.androllm.core.models.download

import com.google.common.truth.Truth.assertThat
import io.androllm.core.models.catalog.CatalogModel
import org.junit.Test

class ModelCompatibilityTest {

    private fun base() = CatalogModel(
        id = "m",
        name = "M",
        family = "Qwen",
        architecture = "qwen3",
        repoId = "r",
        fileName = "m.litertlm",
        downloadUrl = "https://example.com/m.litertlm",
        quantization = "Q8",
        version = "1.0.0",
        fileFormat = "LITERTLM",
        mimeType = "application/x-litertlm",
        containerType = "qwen3",
        runtimeFormat = "LITERTLM",
    )

    @Test
    fun `valid litertlm model passes the gate`() {
        assertThat(ModelCompatibility.check(base()).compatible).isTrue()
    }

    @Test
    fun `gguf artifact is rejected before download`() {
        val result = ModelCompatibility.check(base().copy(fileName = "m.gguf"))
        assertThat(result.compatible).isFalse()
        assertThat(result.reason).contains("GGUF")
    }

    @Test
    fun `litertlm without container type is rejected`() {
        val result = ModelCompatibility.check(base().copy(containerType = null))
        assertThat(result.compatible).isFalse()
        assertThat(result.reason).contains("containerType")
    }

    @Test
    fun `unknown container type is rejected`() {
        val result = ModelCompatibility.check(base().copy(containerType = "qwen99"))
        assertThat(result.compatible).isFalse()
    }

    @Test
    fun `chat model as tflite is rejected`() {
        val result = ModelCompatibility.check(
            base().copy(
                fileName = "m.tflite",
                fileFormat = "TFLITE",
                mimeType = "application/x-tflite",
                containerType = null,
                categories = listOf("CHAT"),
            )
        )
        assertThat(result.compatible).isFalse()
        assertThat(result.reason).contains(".litertlm")
    }

    @Test
    fun `empty backend set is rejected`() {
        val result = ModelCompatibility.check(base().copy(supportedBackends = listOf("QNN")))
        assertThat(result.compatible).isFalse()
        assertThat(result.reason).contains("backend")
    }

    @Test
    fun `legacy gpu backend label passes the gate`() {
        val result = ModelCompatibility.check(base().copy(supportedBackends = listOf("CPU", "GPU")))
        assertThat(result.compatible).isTrue()
    }

    @Test
    fun `missing url is rejected`() {
        val result = ModelCompatibility.check(base().copy(downloadUrl = ""))
        assertThat(result.compatible).isFalse()
    }
}
