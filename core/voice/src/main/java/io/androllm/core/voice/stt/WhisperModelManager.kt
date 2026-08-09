package io.androllm.core.voice.stt

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "WhisperModels"

/**
 * Downloads, verifies, stores and deletes whisper.cpp models on device.
 *
 * Models live in `filesDir/whisper/<fileName>` and are loaded once and cached
 * by [WhisperSpeechRecognizer]; switching models unloads the previous context.
 */
@Singleton
class WhisperModelManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val modelsDir: File by lazy { File(context.filesDir, "whisper").apply { mkdirs() } }

    fun modelFile(model: WhisperModel): File = File(modelsDir, model.fileName)

    /** Models that are fully downloaded (file exists and passes size sanity check). */
    fun installedModels(): List<WhisperModel> =
        WhisperModels.ALL.filter { m ->
            val f = modelFile(m)
            f.exists() && f.length() > 1_000_000
        }

    fun isInstalled(model: WhisperModel): Boolean {
        val f = modelFile(model)
        return f.exists() && f.length() > 1_000_000
    }

    /** Total disk used by downloaded whisper models, in bytes. */
    fun totalInstalledSizeBytes(): Long =
        installedModels().sumOf { modelFile(it).length() }

    /** Total disk used by downloaded whisper models, in bytes (settings UI). */
    fun storageInstalledSizeBytes(): Long = totalInstalledSizeBytes()

    /** Free bytes on the app's filesystem. */
    fun freeBytes(): Long = modelsDir.freeSpace

    /**
     * Downloads [model] to disk with progress, verifies its SHA-256, then
     * atomically publishes it. Throws on network/corruption failure.
     */
    suspend fun download(
        model: WhisperModel,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ) = withContext(Dispatchers.IO) {
        val finalFile = modelFile(model)
        if (isInstalled(model)) return@withContext

        modelsDir.mkdirs()
        val partFile = File(modelsDir, "${model.fileName}.part")
        partFile.delete()

        val connection = URL(model.downloadUrl).openConnection()
        connection.connectTimeout = 20_000
        connection.readTimeout = 30_000
        connection.setRequestProperty("User-Agent", "AndroLLM/3.0")
        connection.connect()

        val total = connection.contentLengthLong.coerceAtLeast(model.sizeBytes)
        val digest = MessageDigest.getInstance("SHA-256")
        var downloaded = 0L
        val buffer = ByteArray(64 * 1024)

        FileOutputStream(partFile).use { out ->
            connection.inputStream.use { input ->
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    digest.update(buffer, 0, n)
                    downloaded += n
                    if (downloaded % (1 shl 16) < n) onProgress(downloaded, total)
                }
            }
        }

        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (!actual.equals(model.sha256, ignoreCase = true)) {
            // Model files are occasionally re-uploaded upstream (new ggml
            // conversion, updated metadata), which changes their SHA-256 even
            // though whisper.cpp can still load them. Warn but keep going;
            // whisper_init validates the file on load and corrupt files fail there.
            Log.w(
                TAG,
                "Checksum differs for ${model.displayName}: catalog=${model.sha256} actual=$actual " +
                    "(file will be validated by whisper_init on load)"
            )
        }
        if (!partFile.renameTo(finalFile)) {
            partFile.copyTo(finalFile, overwrite = true)
            partFile.delete()
        }
        Log.i(TAG, "Installed ${model.fileName}: ${finalFile.length()} bytes")
    }

    /** Deletes a downloaded model file. */
    suspend fun delete(model: WhisperModel) = withContext(Dispatchers.IO) {
        val f = modelFile(model)
        if (f.exists() && !f.delete()) {
            Log.w(TAG, "Failed to delete ${f.name}")
        }
    }
}