package io.androllm.app.auth

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Username + password auth screen backed by the AndroLLM Auth API
 * (auth.genzx.id). Users can register themselves; admins create accounts
 * via the web admin panel. Token is returned by the API and handed to
 * [onAuthSuccess].
 */
private const val AUTH_API = "https://auth.genzx.id"

@Composable
fun UsernameAuthScreen(onAuthSuccess: (Boolean) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var isRegister by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var loggedOut by remember { mutableStateOf(false) }
    var updateMsg by remember { mutableStateOf<String?>(null) }
    var isChecking by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableStateOf<Float?>(null) }
    val alreadyLoggedIn = AuthSession.isLoggedIn() && !loggedOut

    fun checkUpdate() {
        if (isChecking) return
        isChecking = true
        updateMsg = null
        scope.launch {
            val current = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
            val info = Updater.checkForUpdate(current)
            isChecking = false
            if (info.hasUpdate) {
                updateMsg = "Versi baru ${info.latestVersion} tersedia!"
                // auto-download + install kalau izin ada
                if (Updater.canRequestInstall(context)) {
                    downloadProgress = 0f
                    val apk = Updater.downloadApk(info.apkUrl, context, info.sha256) { p ->
                        downloadProgress = p
                    }
                    downloadProgress = null
                    if (apk != null) {
                        Toast.makeText(context, "Pembaruan siap — install", Toast.LENGTH_SHORT).show()
                        Updater.installApk(context, apk)
                    } else {
                        updateMsg = "Gagal mengunduh (signature/network). Coba lagi."
                    }
                } else {
                    Toast.makeText(context, "Izinkan install aplikasi untuk auto-update", Toast.LENGTH_LONG).show()
                    Updater.openInstallPermissionSettings(context)
                }
            } else {
                updateMsg = "Sudah versi terbaru ($current)"
            }
        }
    }

    fun doLogout() {
        AuthSession.clear()
        loggedOut = true
        Toast.makeText(context, "Anda telah keluar", Toast.LENGTH_SHORT).show()
    }

    fun callApi(path: String, body: JSONObject, callback: (JSONObject) -> Unit) {
        scope.launch {
            isLoading = true
            error = null
            val result = withContext(Dispatchers.IO) {
                try {
                    val conn = URL(AUTH_API + path).openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.connectTimeout = 10000
                    conn.readTimeout = 10000
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.outputStream.use { it.write(body.toString().toByteArray()) }
                    val code = conn.responseCode
                    val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                    val text = stream?.let { s ->
                        BufferedReader(InputStreamReader(s)).use { it.readText() }
                    } ?: ""
                    conn.disconnect()
                    Pair(code, text)
                } catch (e: Exception) {
                    Pair(0, e.message ?: "Network error")
                }
            }
            isLoading = false
            val (code, text) = result
            if (code in 200..299) {
                try {
                    callback(JSONObject(text))
                } catch (e: Exception) {
                    error = "Invalid response"
                }
            } else {
                val msg = try { JSONObject(text).optString("detail", "Error $code") } catch (e: Exception) { "Error $code" }
                error = msg
            }
        }
    }

    fun doLogin() {
        if (username.isBlank() || password.isBlank()) { error = "Username dan password wajib"; return }
        val body = JSONObject().put("username", username.trim()).put("password", password)
        callApi("/api/login", body) { resp ->
            val token = resp.optString("token")
            if (token.isNotEmpty()) {
                val user = resp.optJSONObject("user")
                AuthSession.save(
                    token,
                    user?.optString("username") ?: username.trim(),
                    user?.optString("display_name") ?: ""
                )
                onAuthSuccess(false)
            } else error = "Token kosong"
        }
    }

    fun doRegister() {
        if (username.length < 3) { error = "Username minimal 3 karakter"; return }
        if (password.length < 6) { error = "Password minimal 6 karakter"; return }
        val body = JSONObject()
            .put("username", username.trim())
            .put("password", password)
            .put("display_name", displayName.trim())
        callApi("/api/register", body) { resp ->
            val token = resp.optString("token")
            if (token.isNotEmpty()) {
                val user = resp.optJSONObject("user")
                AuthSession.save(
                    token,
                    user?.optString("username") ?: username.trim(),
                    user?.optString("display_name") ?: displayName.trim()
                )
                Toast.makeText(context, "Akun dibuat! Selamat datang ${username.trim()}", Toast.LENGTH_SHORT).show()
                onAuthSuccess(true)
            } else error = "Token kosong"
        }
    }

    if (alreadyLoggedIn) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0D1117))
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("🤖", fontSize = 72.sp)
            Spacer(Modifier.height(12.dp))
            Text(
                "Anda sudah masuk",
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.ExtraBold, color = Color(0xFFE6EDF3))
            )
            Spacer(Modifier.height(8.dp))
            Text(
                AuthSession.username() ?: "",
                style = MaterialTheme.typography.bodyLarge.copy(color = Color(0xFF2EA043))
            )
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = { doLogout() },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDA3633))
            ) {
                Text("Keluar", fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = { loggedOut = true }) {
                Text("Lanjut sebagai tamu", color = Color(0xFF8B949E))
            }
        }
        return
    }

    LaunchedEffect(Unit) {
        // Auto-check update saat masuk (tidak mengganggu — cuma toast kalau ada baru)
        val current = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "1.0"
        val info = Updater.checkForUpdate(current)
        if (info.hasUpdate && Updater.canRequestInstall(context)) {
            val apk = Updater.downloadApk(info.apkUrl, context, info.sha256) { p ->
                downloadProgress = p
            }
            downloadProgress = null
            if (apk != null) {
                Toast.makeText(context, "Pembaruan ${info.latestVersion} siap — install", Toast.LENGTH_LONG).show()
                Updater.installApk(context, apk)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0D1117))
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("🤖", fontSize = 72.sp)
        Spacer(Modifier.height(12.dp))
        Text(
            "Welcome to AndroLLM",
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.ExtraBold, color = Color(0xFFE6EDF3))
        )
        Spacer(Modifier.height(6.dp))
        Text(
            if (isRegister) "Buat akun baru untuk sinkron profil Anda" else "Masuk untuk menyinkronkan profil Anda",
            style = MaterialTheme.typography.bodyMedium.copy(color = Color(0xFF8B949E)),
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(32.dp))

        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("Username") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color(0xFF2EA043),
                unfocusedBorderColor = Color(0xFF30363D),
                focusedLabelColor = Color(0xFF2EA043),
                cursorColor = Color(0xFF2EA043)
            )
        )
        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color(0xFF2EA043),
                unfocusedBorderColor = Color(0xFF30363D),
                focusedLabelColor = Color(0xFF2EA043),
                cursorColor = Color(0xFF2EA043)
            )
        )

        if (isRegister) {
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = displayName,
                onValueChange = { displayName = it },
                label = { Text("Display name (opsional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFF2EA043),
                    unfocusedBorderColor = Color(0xFF30363D),
                    focusedLabelColor = Color(0xFF2EA043),
                    cursorColor = Color(0xFF2EA043)
                )
            )
        }

        error?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, color = Color(0xFFF85149), fontSize = 13.sp, textAlign = TextAlign.Center)
        }

        Spacer(Modifier.height(20.dp))

        Button(
            onClick = { if (isRegister) doRegister() else doLogin() },
            enabled = !isLoading,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF238636))
        ) {
            Text(
                if (isLoading) "Memproses…" else if (isRegister) "Daftar" else "Masuk",
                fontWeight = FontWeight.SemiBold
            )
        }

        Spacer(Modifier.height(8.dp))

        updateMsg?.let {
            Text(it, color = Color(0xFF2EA043), fontSize = 12.sp, textAlign = TextAlign.Center)
        }

        Spacer(Modifier.height(4.dp))

        TextButton(onClick = { checkUpdate() }, enabled = !isChecking) {
            Text(if (isChecking) "Mengecek…" else "Cek Pembaruan", color = Color(0xFF8B949E), fontSize = 12.sp)
        }

        downloadProgress?.let { p ->
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { p },
                modifier = Modifier.fillMaxWidth().height(6.dp),
                color = Color(0xFF2EA043),
                trackColor = Color(0xFF30363D)
            )
            Spacer(Modifier.height(4.dp))
            Text("Mengunduh… ${(p * 100).toInt()}%", color = Color(0xFF8B949E), fontSize = 11.sp)
        }

        Spacer(Modifier.height(12.dp))

        TextButton(onClick = { isRegister = !isRegister; error = null }) {
            Text(
                if (isRegister) "Sudah punya akun? Masuk" else "Belum punya akun? Daftar",
                color = Color(0xFF2EA043)
            )
        }
    }
}
