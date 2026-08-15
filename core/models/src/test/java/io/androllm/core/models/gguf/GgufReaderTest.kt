package io.androllm.core.models.gguf

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Exercises the pure-JVM GGUF reader against a minimal GGUF v3 file built
 * inline: header, metadata KV section, and the tensor index.
 */
class GgufReaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun writeGguf(
        file: File,
        architecture: String = "qwen2",
        withTensorIndex: Boolean = true,
        badMagic: Boolean = false,
    ): File {
        val tensors = listOf(
            Triple("token_embd.weight", intArrayOf(64, 32), 0),
            Triple("blk.0.attn_q.weight", intArrayOf(64, 64), 2),
            Triple("blk.0.attn_k.weight", intArrayOf(64, 64), 2),
            Triple("blk.1.attn_q.weight", intArrayOf(64, 64), 2),
            Triple("output.weight", intArrayOf(32, 64), 0),
        )
        fun strSize(s: String) = 8L + s.toByteArray(Charsets.UTF_8).size
        var meta = 0L
        meta += strSize("general.architecture") + 4 + strSize(architecture)
        meta += strSize("general.size_label") + 4 + strSize("7B")
        meta += strSize("$architecture.context_length") + 4 + 4
        meta += strSize("$architecture.block_count") + 4 + 4
        meta += strSize("$architecture.expert_count") + 4 + 4
        meta += strSize("tokenizer.ggml.model") + 4 + strSize("llama")
        meta += strSize("tokenizer.chat_template") + 4 + strSize("{{ chat }}")

        var tInfo = 0L
        for (t in tensors) tInfo += strSize(t.first) + 4 + 8L * t.second.size + 4 + 8
        val dataStart = ((24 + meta + tInfo + 31) / 32) * 32

        val buf = ByteBuffer.allocate((dataStart + 512).toInt()).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(if (badMagic) 0xDEADBEEF.toInt() else 0x46554747)
        buf.putInt(3)
        buf.putLong(if (withTensorIndex) tensors.size.toLong() else 0L)
        buf.putLong(7L)
        fun wStr(s: String) { val b = s.toByteArray(); buf.putLong(b.size.toLong()).put(b) }
        wStr("general.architecture"); buf.putInt(8); wStr(architecture)
        wStr("general.size_label"); buf.putInt(8); wStr("7B")
        wStr("$architecture.context_length"); buf.putInt(4); buf.putInt(32768)
        wStr("$architecture.block_count"); buf.putInt(4); buf.putInt(2)
        wStr("$architecture.expert_count"); buf.putInt(4); buf.putInt(60)
        wStr("tokenizer.ggml.model"); buf.putInt(8); wStr("llama")
        wStr("tokenizer.chat_template"); buf.putInt(8); wStr("{{ chat }}")
        if (withTensorIndex) {
            for (t in tensors) {
                wStr(t.first)
                buf.putInt(t.second.size)
                t.second.forEach { buf.putLong(it.toLong()) }
                buf.putInt(t.third)
                buf.putLong(0L)
            }
        }
        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(24 + meta + tInfo)
            raf.seek(0)
            raf.write(buf.array(), 0, (24 + meta + tInfo).toInt())
        }
        return file
    }

    @Test
    fun `parses metadata kv and architecture`() {
        val read = GgufReader.parse(writeGguf(tmp.newFile("model.gguf")))
        assertThat(read.version).isEqualTo(3)
        assertThat(read.architecture).isEqualTo("qwen2")
        assertThat(read.int("qwen2.context_length")).isEqualTo(32768)
        assertThat(read.int("qwen2.block_count")).isEqualTo(2)
        assertThat(read.int("qwen2.expert_count")).isEqualTo(60)
        assertThat(read.kv["tokenizer.ggml.model"]).isEqualTo("llama")
        assertThat(read.kv["tokenizer.chat_template"]).isEqualTo("{{ chat }}")
    }

    @Test
    fun `reads the tensor index with types and dims`() {
        val read = GgufReader.parse(writeGguf(tmp.newFile("model.gguf")))
        assertThat(read.tensorCount).isEqualTo(5)
        assertThat(read.tensors).hasSize(5)
        val attnQ = read.tensors.first { it.name == "blk.0.attn_q.weight" }
        assertThat(attnQ.type).isEqualTo(GgufType.Q4_0)
        assertThat(attnQ.dimensions.toList()).containsExactly(64L, 64L)
        assertThat(read.tensors.first { it.name == "token_embd.weight" }.type).isEqualTo(GgufType.F32)
    }

    @Test
    fun `rejects invalid magic`() {
        val e = runCatching { GgufReader.parse(writeGguf(tmp.newFile("bad.gguf"), badMagic = true)) }.exceptionOrNull()
        assertThat(e).isInstanceOf(InvalidGgufException::class.java)
    }

    @Test
    fun `rejects missing file`() {
        val e = runCatching { GgufReader.parse(File(tmp.root, "nope.gguf")) }.exceptionOrNull()
        assertThat(e).isInstanceOf(java.io.IOException::class.java)
    }

    @Test
    fun `survives a file without tensor index`() {
        val read = GgufReader.parse(writeGguf(tmp.newFile("no-tensors.gguf"), withTensorIndex = false))
        assertThat(read.tensors).isEmpty()
        assertThat(read.architecture).isEqualTo("qwen2")
    }
}