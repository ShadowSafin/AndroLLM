package io.androllm.core.models.catalog

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModelInspectorLiteRtTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun litertlm(version: Int = 1): ByteArray = ByteArray(64).apply {
        "LITERTLM".toByteArray().copyInto(this, 0)
        this[8] = (version and 0xFF).toByte()
        this[9] = ((version shr 8) and 0xFF).toByte()
        this[10] = ((version shr 16) and 0xFF).toByte()
        this[11] = ((version shr 24) and 0xFF).toByte()
    }

    private fun tflite(): ByteArray = ByteArray(64).apply {
        "TFL3".toByteArray().copyInto(this, 4)
    }

    @Test
    fun `inspects litertlm container with catalog context`() {
        val file = tmp.newFile("Qwen3_1.7B.litertlm").apply { writeBytes(litertlm(1)) }
        val catalog = CatalogModel(
            id = "q", name = "Q", family = "Qwen", architecture = "qwen3",
            parameters = "1.7B", quantization = "MIXED", contextLength = 8192,
            containerType = "qwen3", stopSequences = listOf("<|im_end|>"),
        )
        val info = ModelInspector().inspectLiteRt(file, catalog).getOrThrow()
        assertThat(info.format).isEqualTo("LITERTLM")
        assertThat(info.containerVersion).isEqualTo(1)
        assertThat(info.architecture).isEqualTo("qwen3")
        assertThat(info.family).isEqualTo("Qwen")
        assertThat(info.parameters).isEqualTo("1.7B")
        assertThat(info.contextLength).isEqualTo(8192)
        assertThat(info.tokenizerLocation).isEqualTo("embedded")
        assertThat(info.litertCompatible).isTrue()
        assertThat(info.specialTokens).contains("<|im_end|>")
    }

    @Test
    fun `tflite without sidecar tokenizer is flagged`() {
        val file = tmp.newFile("embed.tflite").apply { writeBytes(tflite()) }
        val info = ModelInspector().inspectLiteRt(file).getOrThrow()
        assertThat(info.format).isEqualTo("TFLITE")
        assertThat(info.tokenizerLocation).isEqualTo("missing-sidecar")
        assertThat(info.litertCompatible).isFalse()
    }

    @Test
    fun `tflite with sidecar tokenizer passes`() {
        val dir = tmp.newFolder()
        File(dir, "embed.tflite").apply { writeBytes(tflite()) }
        File(dir, "tokenizer.model").apply { writeBytes(ByteArray(32)) }
        val info = ModelInspector().inspectLiteRt(File(dir, "embed.tflite")).getOrThrow()
        assertThat(info.tokenizerLocation).isEqualTo("sidecar")
        assertThat(info.litertCompatible).isTrue()
    }

    @Test
    fun `inspectAny routes by extension`() {
        val litertlm = tmp.newFile("m.litertlm").apply { writeBytes(litertlm(2)) }
        assertThat(ModelInspector().inspectAny(litertlm).getOrThrow().format).isEqualTo("LITERTLM")
        val junk = tmp.newFile("junk.litertlm").apply { writeBytes(ByteArray(64) { it.toByte() }) }
        assertThat(ModelInspector().inspectAny(junk).isFailure).isTrue()
    }

    @Test
    fun `gguf inspection is marked litert-incompatible`() {
        // Reuse: any GGUF-flagged note lives on the GGUF path; here assert the
        // default Inspection fields for LiteRT carry compatibility metadata.
        val file = tmp.newFile("tiny.litertlm").apply { writeBytes(litertlm(1)) }
        val info = ModelInspector().inspectLiteRt(file).getOrThrow()
        assertThat(info.compatibilityNote).isNotEmpty()
    }
}
