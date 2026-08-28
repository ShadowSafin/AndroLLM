package io.androllm.feature.coding.environment.proot

import android.content.Context
import io.androllm.feature.coding.environment.RootfsTarballProvider
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber

/**
 * Android bindings for the proot Linux base.
 *
 * The proot binaries ship as native libraries in the APK (see [ProotFiles]);
 * the Debian rootfs tarball is downloaded on first use by
 * [DebianRootfsDownloader] and cached in app storage (~90 MB — kept out of
 * the APK on purpose).
 */
class AndroidProotFiles(private val context: Context) {
    fun get(): ProotFiles = ProotFiles(
        nativeLibDir = File(context.applicationInfo.nativeLibraryDir),
        rootfsDir = File(File(context.filesDir, "coding-env"), "rootfs"),
        tmpDir = File(File(context.filesDir, "coding-env"), "tmp")
    )
}

/**
 * Downloads and caches the official Debian rootfs tarball (arm64, "trixie",
 * built by the Linux Containers image service from pristine Debian packages).
 *
 * The tarball is fetched once (~90 MB), SHA-256 verified, and cached in the
 * app's files dir; re-provisioning after "remove base" reuses the cache.
 * Downloads stream through OkHttp — the same network stack the app's chat
 * uses, which is known-good on the device (unlike guest-side fetches that
 * must pass through proot).
 */
class DebianRootfsDownloader(
    private val cacheDir: File,
    private val client: OkHttpClient = OkHttpClient(),
    private val url: String = DEFAULT_URL,
    private val expectedSize: Long = EXPECTED_SIZE,
    private val expectedSha256: String = EXPECTED_SHA256
) : RootfsTarballProvider {

    override fun cachePath(): File = File(cacheDir, TARBALL_NAME)

    override suspend fun ensureTarball(onProgress: (Long, Long) -> Unit): File = withContext(Dispatchers.IO) {
        val target = cachePath()
        if (target.exists() && target.length() == expectedSize && sha256(target) == expectedSha256) {
            Timber.i("Debian rootfs tarball cached: %s", target)
            onProgress(expectedSize, expectedSize)
            return@withContext target
        }
        if (target.exists()) target.delete()
        cacheDir.mkdirs()

        Timber.i("Downloading Debian rootfs from %s", url)
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("Debian base download failed: HTTP ${response.code}")
            }
            val body = response.body ?: throw IllegalStateException("Debian base download: empty response")
            val total = body.contentLength().takeIf { it > 0 } ?: expectedSize
            val tmp = File(cacheDir, "$TARBALL_NAME.part")
            var done = 0L
            tmp.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                val stream = body.byteStream()
                while (true) {
                    val r = stream.read(buf)
                    if (r < 0) break
                    out.write(buf, 0, r)
                    done += r
                    onProgress(done, total)
                }
            }
            if (done != expectedSize) {
                tmp.delete()
                throw IllegalStateException("Debian base download truncated: $done of $expectedSize bytes")
            }
            val hash = sha256(tmp)
            if (hash != expectedSha256) {
                tmp.delete()
                throw IllegalStateException("Debian base checksum mismatch: $hash")
            }
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
            Timber.i("Debian rootfs downloaded OK (%d bytes)", done)
        }
        target
    }

    private fun sha256(file: File): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val r = input.read(buf)
                if (r < 0) break
                md.update(buf, 0, r)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val TARBALL_NAME = "debian-trixie-arm64-rootfs.tar.xz"

        /**
         * Official Linux Containers image of Debian trixie (arm64). Pinned to a
         * dated build so the URL — and the checksum below — never silently move.
         */
        const val DEFAULT_URL =
            "https://images.linuxcontainers.org/images/debian/trixie/arm64/default/20260827_05%3A24/rootfs.tar.xz"

        const val EXPECTED_SIZE = 90_319_208L
        const val EXPECTED_SHA256 = "1914201cf0f056d4b4f6c1bbaf9a3897744a6d0974065736db42a378f06050dc"

        fun forApp(context: Context): DebianRootfsDownloader =
            DebianRootfsDownloader(File(context.filesDir, "coding-env"))
    }
}
