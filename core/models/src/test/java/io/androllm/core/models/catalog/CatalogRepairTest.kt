package io.androllm.core.models.catalog

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CatalogRepairTest {

    private fun base() = CatalogModel(
        id = "m",
        name = "M",
        family = "Qwen",
        architecture = "qwen3",
        repoId = "r",
        fileName = "m.litertlm",
        downloadUrl = "https://example.com/m.litertlm",
        quantization = "Q8",
        runtimeFormat = "LITERTLM",
    )

    @Test
    fun `repair derives format mime version and container type`() {
        val (models, notes) = CatalogRepair.repairAll(listOf(base())).let { it.models to it.notes }
        val repaired = models.single()
        assertThat(repaired.fileFormat).isEqualTo("LITERTLM")
        assertThat(repaired.mimeType).isEqualTo("application/x-litertlm")
        assertThat(repaired.version).isEqualTo("1.0.0")
        assertThat(repaired.containerType).isEqualTo("generic_model")
        assertThat(notes).isNotEmpty()
    }

    @Test
    fun `repair normalizes legacy gpu backend to vulkan`() {
        val repaired = CatalogRepair.repairAll(
            listOf(base().copy(supportedBackends = listOf("CPU", "GPU")))
        ).models.single()
        assertThat(repaired.supportedBackends).containsExactly("CPU", "VULKAN")
    }

    @Test
    fun `repair mirrors supportsVulkan from supportsGpu`() {
        val repaired = CatalogRepair.repairAll(
            listOf(base().copy(supportsGpu = true, supportsVulkan = false))
        ).models.single()
        assertThat(repaired.supportsVulkan).isTrue()
    }

    @Test
    fun `repaired catalog passes validation`() {
        val repaired = CatalogRepair.repairAll(
            listOf(
                base().copy(supportedBackends = listOf("CPU", "GPU")),
                base().copy(
                    id = "s",
                    family = "Whisper",
                    architecture = "whisper",
                    fileName = "w.tflite",
                    runtimeFormat = "TFLITE",
                ),
            )
        ).models
        val report = CatalogValidator.validate(repaired)
        assertThat(report.errors).isEmpty()
    }
}
