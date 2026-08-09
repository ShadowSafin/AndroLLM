package io.androllm.app.auth

import android.content.Context
import android.content.SharedPreferences

/**
 * Sesi auth sederhana: simpan JWT token + username dari auth.genzx.id
 * di SharedPreferences. Cukup untuk "login nyata" — token dipakai
 * sebagai Bearer header pada request terproteksi.
 */
object AuthSession {
    private const val PREFS = "androllm_auth"
    private const val KEY_TOKEN = "token"
    private const val KEY_USERNAME = "username"
    private const val KEY_DISPLAY = "display_name"

    @Volatile
    private var appContext: Context? = null

    /** Wajib dipanggil sekali di Application.onCreate(). */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private fun prefs(): SharedPreferences {
        val ctx = appContext ?: throw IllegalStateException("AuthSession.init() belum dipanggil")
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    fun save(token: String, username: String, displayName: String = "") {
        prefs().edit()
            .putString(KEY_TOKEN, token)
            .putString(KEY_USERNAME, username)
            .putString(KEY_DISPLAY, displayName)
            .apply()
    }

    fun token(): String? = prefs().getString(KEY_TOKEN, null)

    fun username(): String? = prefs().getString(KEY_USERNAME, null)

    fun displayName(): String? = prefs().getString(KEY_DISPLAY, null)

    fun isLoggedIn(): Boolean = runCatching { !token().isNullOrEmpty() }.getOrDefault(false)

    fun clear() {
        prefs().edit().clear().apply()
    }
}
