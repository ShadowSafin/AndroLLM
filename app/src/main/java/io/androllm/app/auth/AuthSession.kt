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

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun save(ctx: Context, token: String, username: String, displayName: String = "") {
        prefs(ctx).edit()
            .putString(KEY_TOKEN, token)
            .putString(KEY_USERNAME, username)
            .putString(KEY_DISPLAY, displayName)
            .apply()
    }

    fun token(ctx: Context): String? = prefs(ctx).getString(KEY_TOKEN, null)

    fun username(ctx: Context): String? = prefs(ctx).getString(KEY_USERNAME, null)

    fun displayName(ctx: Context): String? = prefs(ctx).getString(KEY_DISPLAY, null)

    fun isLoggedIn(ctx: Context): Boolean = !token(ctx).isNullOrEmpty()

    fun clear(ctx: Context) {
        prefs(ctx).edit().clear().apply()
    }
}
