package io.androllm.app.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Auto-update dari GitHub Release.
 *
 * Cek release terbaru via GitHub API, bandingkan dengan versionName lokal,
 * kalau ada versi baru -> download APK ke cache + minta install.
 * URL: https://github.com/genzxproject/AndroLLM/releases
 */
object Updater {
    private const val REPO = "genzxproject/AndroLLM"
    private const val GITHUB_API = "https://api.github.com/repos/$REPO/releases/latest"
    private const val GITHUB_DL = "https://github.com/$REPO/releases/download"

    data class UpdateInfo(
        val hasUpdate: Boolean,
        val latestVersion: String = "",
        val apkUrl: String = "",
<<<<<<< HEAD
        val notes: String = "",
        val sha256: String = ""
=======
        val notes: String = ""
>>>>>>> origin/main
    )

    suspend fun checkForUpdate(currentVersion: String): UpdateInfo = withContext(Dispatchers.IO) {
        try {
            val conn = URL(GITHUB_API).openConnection() as HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            val code = conn.responseCode
            if (code != 200) return@withContext UpdateInfo(false)
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()

            val json = JSONObject(text)
            val tag = json.optString("tag_name", "")
            val latestVer = tag.removePrefix("v")
<<<<<<< HEAD
            var apkUrl = ""
            var sha256 = ""
            json.optJSONArray("assets")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val a = arr.getJSONObject(i)
                    if (a.optString("name", "").endsWith(".apk")) {
                        apkUrl = a.optString("browser_download_url", "")
                        sha256 = a.optJSONObject("digest")?.optString("sha256", "") ?: ""
                        break
                    }
                }
            }
=======
            val apkUrl = json.optJSONArray("assets")?.let { arr ->
                (0 until arr.length()).firstOrNull { i ->
                    arr.getJSONObject(i).optString("name", "").endsWith(".apk")
                }?.let { arr.getJSONObject(it).optString("browser_download_url", "") }
            } ?: ""
>>>>>>> origin/main
            val notes = json.optString("body", "")

            UpdateInfo(
                hasUpdate = compareVersions(latestVer, currentVersion) > 0,
                latestVersion = latestVer,
                apkUrl = apkUrl,
<<<<<<< HEAD
                notes = notes,
                sha256 = sha256
=======
                notes = notes
>>>>>>> origin/main
            )
        } catch (e: Exception) {
            UpdateInfo(false)
        }
    }

    /** 1.0.0 < 1.2.0 -> -1. Kembalikan 0 kalau sama. */
    private fun compareVersions(a: String, b: String): Int {
        val pa = a.split(".").map { it.toIntOrNull() ?: 0 }
        val pb = b.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return if (x > y) 1 else -1
        }
        return 0
    }

<<<<<<< HEAD
    /** Download APK dari URL ke cache dir, verifikasi SHA-256 kalau disediakan. */
    suspend fun downloadApk(
        apkUrl: String,
        context: Context,
        expectedSha256: String = "",
        onProgress: (Float) -> Unit = {}
    ): File? = withContext(Dispatchers.IO) {
=======
    /** Download APK dari URL ke cache dir. */
    suspend fun downloadApk(apkUrl: String, context: Context): File? = withContext(Dispatchers.IO) {
>>>>>>> origin/main
        try {
            val outFile = File(context.cacheDir, "androllm-update.apk")
            val conn = URL(apkUrl).openConnection() as HttpURLConnection
            conn.connectTimeout = 20000
<<<<<<< HEAD
            conn.readTimeout = 120000
            conn.instanceFollowRedirects = true
            if (conn.responseCode !in 200..299) return@withContext null
            val total = conn.contentLength.toLong()
            var downloaded = 0L
            val buf = ByteArray(64 * 1024)
            conn.inputStream.use { input ->
                FileOutputStream(outFile).use { output ->
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        downloaded += n
                        if (total > 0) {
                            onProgress((downloaded.toFloat() / total.toFloat()).coerceIn(0f, 1f))
                        }
                    }
                }
            }
            conn.disconnect()
            if (expectedSha256.isNotEmpty()) {
                val actual = outFile.inputStream().use { it.readBytes().let { b ->
                    java.security.MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
                } }
                if (!actual.equals(expectedSha256, ignoreCase = true)) {
                    outFile.delete()
                    return@withContext null
                }
            }
=======
            conn.readTimeout = 30000
            conn.instanceFollowRedirects = true
            if (conn.responseCode !in 200..299) return@withContext null
            conn.inputStream.use { input ->
                FileOutputStream(outFile).use { output ->
                    input.copyTo(output)
                }
            }
            conn.disconnect()
>>>>>>> origin/main
            outFile
        } catch (e: Exception) {
            null
        }
    }

    /** Minta install APK (butuh izin "Install unknown apps" untuk app ini). */
    fun installApk(context: Context, apkFile: File) {
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }

    /** Buka halaman izin "Install unknown apps". */
    fun openInstallPermissionSettings(context: Context) {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}")
        )
        context.startActivity(intent)
    }

    /** Cek apakah app punya izin install unknown apps. */
    fun canRequestInstall(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else true
    }
}
