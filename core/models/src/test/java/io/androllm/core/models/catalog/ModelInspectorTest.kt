package io.androllm.core.models.catalog

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Builds a minimal valid GGUF v3 file inline and verifies the inspector
 * derives architecture, quantization, parameters, tokenizer and backends
 * from bytes — never from the filename.
 */
class ModelInspectorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun writeGguf(
        file: File,
        architecture: String = "qwen2",
        vocab: List<String> = listOf("<s>", "</s>", "Hello", " world"),
        expertCount: Int = 0,
    ): File {
        data class T(val name: String, val dims: IntArray, val typeId: Int)
        val tensors = listOf(
            T("token_embd.weight", intArrayOf(64, 32), 0),   // F32
            T("blk.0.attn_q.weight", intArrayOf(64, 64), 2), // Q4_0
            T("blk.0.attn_q.weight2", intArrayOf(64, 64), 2),
            T("blk.0.attn_k.weight", intArrayOf(64, 64), 2),
            T("blk.1.attn_q.weight", intArrayOf(64, 64), 2),
            T("output.weight", intArrayOf(32, 64), 0),
        )
        // Compute sizes.
        var meta = 0L
        fun strSize(s: String) = 8L + s.toByteArray(Charsets.UTF_8).size
        meta += strSize("general.architecture") + 4 + strSize(architecture)
        meta += strSize("general.size_label") + 4 + strSize("7B")
        meta += strSize("$architecture.context_length") + 4 + 4
        meta += strSize("$architecture.embedding_length") + 4 + 4
        meta += strSize("$architecture.block_count") + 4 + 4
        meta += strSize("$architecture.attention.head_count") + 4 + 4
        meta += strSize("$architecture.attention.head_count_kv") + 4 + 4
        if (expertCount > 0) meta += strSize("$architecture.expert_count") + 4 + 4
        meta += strSize("tokenizer.ggml.model") + 4 + strSize("llama")
        meta += strSize("tokenizer.ggml.tokens") + 4 + 4 + 8 + vocab.sumOf { strSize(it) }
        var tInfo = 0L
        for (t in tensors) tInfo += strSize(t.name) + 4 + 8L * t.dims.size + 4 + 8
        val dataStart = align(24 + meta + tInfo, 32L)

        var cursor = 0L
        data class Info(val name: String, val dims: IntArray, val typeId: Int, val offset: Long)
        val infos = ArrayList<Info>()
        for (t in tensors) {
            val a = align(cursor, 32L)
            infos += Info(t.name, t.dims, t.typeId, a)
            val elems = t.dims.fold(1L) { a2, b -> a2 * b }
            val size = if (t.typeId == 0) elems * 4 else ((elems + 31) / 32) * 18
            cursor = a + size
        }
        val kvCount = if (expertCount > 0) 10 else 9
        val buf = ByteBuffer.allocate((dataStart + cursor).toInt()).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(0x46554747).putInt(3).putLong(tensors.size.toLong()).putLong(kvCount.toLong())
        fun wStr(s: String) { val b = s.toByteArray(); buf.putLong(b.size.toLong()).put(b) }
        wStr("general.architecture"); buf.putInt(8); wStr(architecture)
        wStr("general.size_label"); buf.putInt(8); wStr("7B")
        wStr("$architecture.context_length"); buf.putInt(4); buf.putInt(32768)
        wStr("$architecture.embedding_length"); buf.putInt(4); buf.putInt(64)
        wStr("$architecture.block_count"); buf.putInt(4); buf.putInt(2)
        wStr("$architecture.attention.head_count"); buf.putInt(4); buf.putInt(8)
        wStr("$architecture.attention.head_count_kv"); buf.putInt(4); buf.putInt(4)
        if (expertCount > 0) { wStr("$architecture.expert_count"); buf.putInt(4); buf.putInt(expertCount) }
        wStr("tokenizer.ggml.model"); buf.putInt(8); wStr("llama")
        wStr("tokenizer.ggml.tokens"); buf.putInt(9); buf.putInt(8); buf.putLong(vocab.size.toLong())
        vocab.forEach { wStr(it) }
        for (info in infos) {
            wStr(info.name); buf.putInt(info.dims.size)
            info.dims.forEach { buf.putLong(it.toLong()) }
            buf.putInt(info.typeId); buf.putLong(info.offset)
        }
        buf.position(dataStart.toInt())
        for (info in infos) {
            while (buf.position() < dataStart + info.offset) buf.put(0)
            val elems = info.dims.fold(1L) { a2, b -> a2 * b }
            val size = if (info.typeId == 0) elems * 4 else ((elems + 31) / 32) * 18
            buf.put(ByteArray(size.toInt()))
        }
        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(buf.position().toLong())
            raf.seek(0)
            raf.write(buf.array(), 0, buf.position())
        }
        return file
    }

    private fun align(v: Long, a: Long): Long = (v + a - 1) / a * a

    @Test
    fun `inspects architecture quantization parameters and tokenizer from the file`() {
        val file = writeGguf(tmp.newFile("renamed.bin")) // misleading name on purpose
        val result = ModelInspector().inspect(file)

        assertThat(result.isSuccess).isTrue()
        val info = result.getOrThrow()
        assertThat(info.architecture).isEqualTo("qwen2")
        assertThat(info.family).isEqualTo("Qwen")
        assertThat(info.quantization).isEqualTo("Q4_0") // dominant block type
        assertThat(info.parameters).isEqualTo("7B")     // from general.size_label
        assertThat(info.contextLength).isEqualTo(32768)
        assertThat(info.blockCount).isEqualTo(2)
        assertThat(info.tokenizerModel).isEqualTo("llama")
        assertThat(info.streamable).isTrue()
        assertThat(info.supportedBackends.map { it.name }).containsExactly("CPU", "VULKAN")
        // Never an NPU backend.
        assertThat(info.supportedBackends.map { it.name }).doesNotContain("NPU")
    }

    @Test
    fun `detects MoE architecture from expert metadata`() {
        val file = writeGguf(tmp.newFile("moe.bin"), architecture = "qwen2moe", expertCount = 60)
        val info = ModelInspector().inspect(file).getOrThrow()

        assertThat(info.isMoe).isTrue()
        assertThat(info.expertCount).isEqualTo(60)
        assertThat(info.denseOrMoe).isEqualTo(DenseOrMoe.MOE)
        assertThat(info.modelStreamType).isEqualTo(ModelStreamType.STREAMING_MOE)
        assertThat(info.streamable).isTrue()
    }

    @Test
    fun `rejects non-gguf files`() {
        val junk = tmp.newFile("junk.bin")
        junk.writeBytes(ByteArray(256) { it.toByte() })
        assertThat(ModelInspector().inspect(junk).isFailure).isTrue()
    }

    @Test
    fun `streaming estimate separates storage from ram`() {
        val model = CatalogModel(
            id = "qwen7b",
            name = "Qwen 7B Q4_K",
            family = "Qwen",
            architecture = "qwen2",
            parameters = "7B",
            quantization = "Q4_K",
            sizeBytes = 4_200_000_000, // 4.2 GB storage
        )
        val ram = model.estimatedRuntimeRamMbValue
        // ~28% of 4.2 GB ≈ 1.2 GB resident — the whole point of streaming.
        assertThat(ram).isIn(900L..2000L)
        assertThat(ram).isLessThan(4_200L)
    }

    @Test
    fun `recommendation tiers use runtime ram not storage size`() {
        val small = CatalogModel(
            id = "a", name = "A", quantization = "Q4_K", parameters = "3B",
            sizeBytes = 2_000_000_000, // 2 GB storage, ~0.56 GB resident
        )
        val heavy = CatalogModel(
            id = "b", name = "B", quantization = "Q4_K", parameters = "70B",
            sizeBytes = 40_000_000_000, // 40 GB storage, ~11 GB resident
        )
        val recs = RecommendationEngine.recommend(listOf(small, heavy), deviceRamGb = 8.0f)
        val byId = recs.associateBy { it.model.id }
        assertThat(byId["a"]!!.tier).isEqualTo(RecommendationEngine.Tier.RECOMMENDED)
        assertThat(byId["b"]!!.tier).isEqualTo(RecommendationEngine.Tier.NOT_RECOMMENDED)
        // The 2 GB file is RECOMMENDED on 8 GB — storage size alone would have
        // said "fits" for the 40 GB file too; runtime RAM is what gates it.
        assertThat(byId["b"]!!.reasons.any { it.contains("heavy") }).isTrue()
    }
}
