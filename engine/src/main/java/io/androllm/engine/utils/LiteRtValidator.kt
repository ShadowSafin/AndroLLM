package io.androllm.engine.utils

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/**
 * Validation of LiteRT model artifacts before they are loaded by the engine.
 *
 * Replaces the (removed) llama.cpp `GgufValidator`. The LiteRT runtime accepts
 * two artifact types:
 *
 *  - `.litertlm` — the LiteRT-LM container. The first 8 bytes are the ASCII
 *    magic `LITERTLM`, followed by a uint32 format version.
 *  - `.tflite` — a plain TensorFlow Lite flatbuffer. Per the flatbuffer
 *    convention the 4-byte file identifier `TFL3` lives at offset 4..7.
 *
 * Both checks are cheap header reads — a model is only marked Ready by the
 * engine after the real runtime initializes it and a smoke test passes; this
 * class exists to reject obviously-wrong files (renamed downloads, truncated
 * transfers, GGUF leftovers) before the runtime spends minutes on them.
 */
object LiteRtValidator {

    /** ASCII `LITERTLM` — the .litertlm container magic. */
    private val LITERTLM_MAGIC = byteArrayOf(
        'L'.code.toByte(), 'I'.code.toByte(), 'T'.code.toByte(), 'E'.code.toByte(),
        'R'.code.toByte(), 'T'.code.toByte(), 'L'.code.toByte(), 'M'.code.toByte()
    )

    /** TFLite flatbuffer file identifier (offset 4..7). */
    private val TFLITE_FILE_ID = byteArrayOf(
        'T'.code.toByte(), 'F'.code.toByte(), 'L'.code.toByte(), '3'.code.toByte()
    )

    /** Outcome of a header validation. */
    data class ArtifactValidation(
        val isValid: Boolean,
        val format: String = "", // "litertlm" | "tflite" | ""
        val version: Int = 0,    // .litertlm container format version
        val errorMessage: String = ""
    )

    /**
     * Validates the artifact header at [path]. Returns [ArtifactValidation.isValid]
     * = false (never throws) when the file is missing, unreadable, too small to
     * carry a header, or has neither recognized magic.
     */
    fun validateHeader(path: String): ArtifactValidation {
        val file = File(path)
        if (!file.exists()) return ArtifactValidation(false, errorMessage = "File not found: $path")
        if (!file.canRead()) return ArtifactValidation(false, errorMessage = "File not readable: $path")

        return try {
            FileInputStream(file).use { input ->
                val header = ByteArray(12)
                val read = input.read(header)
                if (read < 12) {
                    return ArtifactValidation(false, errorMessage = "File too small to be a model artifact: $path")
                }

                when {
                    header.copyOfRange(0, 8).contentEquals(LITERTLM_MAGIC) -> {
                        val version = (header[8].toInt() and 0xFF) or
                            ((header[9].toInt() and 0xFF) shl 8) or
                            ((header[10].toInt() and 0xFF) shl 16) or
                            ((header[11].toInt() and 0xFF) shl 24)
                        if (version < 1) {
                            ArtifactValidation(false, errorMessage = "Unsupported .litertlm container version $version")
                        } else {
                            ArtifactValidation(true, format = "litertlm", version = version)
                        }
                    }

                    header.copyOfRange(4, 8).contentEquals(TFLITE_FILE_ID) -> {
                        ArtifactValidation(true, format = "tflite")
                    }

                    else -> ArtifactValidation(
                        false,
                        errorMessage = "Not a LiteRT model: expected a .litertlm container (magic \"LITERTLM\") " +
                            "or a .tflite flatbuffer (file id \"TFL3\"). The file may be a GGUF model, " +
                            "a partial download, or a renamed file."
                    )
                }
            }
        } catch (e: Exception) {
            ArtifactValidation(false, errorMessage = "Artifact read failed: ${e.message}")
        }
    }

    /**
     * SHA-256 of the file at [path], or null when unreadable. Used to verify
     * downloaded artifacts against the checksum published in the catalog.
     */
    fun calculateSha256(path: String): String? = try {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(File(path)).use { input ->
            val buffer = ByteArray(64 * 1024)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    } catch (e: Exception) {
        null
    }
}
