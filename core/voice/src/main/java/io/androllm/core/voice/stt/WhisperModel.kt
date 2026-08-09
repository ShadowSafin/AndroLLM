package io.androllm.core.voice.stt

/**
 * A downloadable whisper.cpp model (pre-converted ggml format).
 *
 * Sizes and SHA-256 checksums are taken from the official whisper.cpp
 * models/README.md; binaries live on huggingface.co/ggerganov/whisper.cpp.
 */
data class WhisperModel(
    val id: String,
    val fileName: String,
    val displayName: String,
    val sizeBytes: Long,
    val sha256: String,
    val multilingual: Boolean,
    /** 0 = low-end, 1 = mid-range, 2 = high-end (Snapdragon 8 Gen 3+). */
    val tier: Int
) {
    val downloadUrl: String
        get() = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/$fileName"

    val sizeLabel: String
        get() = when {
            sizeBytes >= 1024 * 1024 * 1024 -> String.format("%.1f GB", sizeBytes / (1024.0 * 1024 * 1024))
            else -> String.format("%d MB", sizeBytes / (1024 * 1024))
        }
}

object WhisperModels {

    private val MiB = 1024L * 1024L

    val ALL: List<WhisperModel> = listOf(
        WhisperModel(
            id = "tiny.en", fileName = "ggml-tiny.en.bin", displayName = "Tiny (English)",
            sizeBytes = 75 * MiB, sha256 = "c78c86eb1a8faa21b369bcd33207cc90d64ae9df",
            multilingual = false, tier = 0
        ),
        WhisperModel(
            id = "tiny", fileName = "ggml-tiny.bin", displayName = "Tiny (Multilingual)",
            sizeBytes = 75 * MiB, sha256 = "bd577a113a864445d4c299885e0cb97d4ba92b5f",
            multilingual = true, tier = 0
        ),
        WhisperModel(
            id = "base.en", fileName = "ggml-base.en.bin", displayName = "Base (English)",
            sizeBytes = 142 * MiB, sha256 = "137c40403d78fd54d454da0f9bd998f78703390c",
            multilingual = false, tier = 1
        ),
        WhisperModel(
            id = "base", fileName = "ggml-base.bin", displayName = "Base (Multilingual)",
            sizeBytes = 142 * MiB, sha256 = "465707469ff3a37a2b9b8d8f89f2f99de7299dac",
            multilingual = true, tier = 1
        ),
        WhisperModel(
            id = "small.en", fileName = "ggml-small.en.bin", displayName = "Small (English)",
            sizeBytes = 466 * MiB, sha256 = "db8a495a91d927739e50b3fc1cc4c6b8f6c2d022",
            multilingual = false, tier = 1
        ),
        WhisperModel(
            id = "small", fileName = "ggml-small.bin", displayName = "Small (Multilingual)",
            sizeBytes = 466 * MiB, sha256 = "55356645c2b361a969dfd0ef2c5a50d530afd8d5",
            multilingual = true, tier = 1
        ),
        WhisperModel(
            id = "medium.en", fileName = "ggml-medium.en.bin", displayName = "Medium (English)",
            sizeBytes = 1536 * MiB, sha256 = "8c30f0e44ce9560643ebd10bbe50cd20eafd3723",
            multilingual = false, tier = 2
        ),
        WhisperModel(
            id = "large-v3-turbo", fileName = "ggml-large-v3-turbo.bin", displayName = "Large v3 Turbo",
            sizeBytes = 1536 * MiB, sha256 = "4af2b29d7ec73d781377bfd1758ca957a807e941",
            multilingual = true, tier = 2
        )
    )

    fun byId(id: String): WhisperModel? = ALL.firstOrNull { it.id == id }

    /**
     * Recommended default model for the device: large-v3-turbo on high-end
     * SoCs, small on mid-range, base otherwise. Falls back to base.en.
     */
    fun recommendedForDevice(totalRamGb: Long): WhisperModel {
        val preferred = when {
            totalRamGb >= 12 -> "large-v3-turbo"
            totalRamGb >= 6 -> "small"
            else -> "base.en"
        }
        return byId(preferred) ?: byId("base.en")!!
    }
}
