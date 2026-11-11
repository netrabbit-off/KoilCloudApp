package com.thekoil.koilcloud.auth

import android.content.Context

object AuthManager {
    private const val PREFS = "koilcloud_auth"
    private const val KEY_ACCESS = "access_token"
    private const val KEY_REFRESH = "refresh_token"
    private const val KEY_LOGIN = "login"
    private const val KEY_USER_ID = "user_id"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun save(context: Context, access: String, refresh: String, login: String, userId: String? = null) {
        prefs(context).edit()
            .putString(KEY_ACCESS, access)
            .putString(KEY_REFRESH, refresh)
            .putString(KEY_LOGIN, login)
            .putString(KEY_USER_ID, userId)
            .apply()
    }

    fun saveTokens(context: Context, access: String, refresh: String) {
        prefs(context).edit()
            .putString(KEY_ACCESS, access)
            .putString(KEY_REFRESH, refresh)
            .apply()
    }

    fun getAccessToken(context: Context): String? = prefs(context).getString(KEY_ACCESS, null)
    fun getRefreshToken(context: Context): String? = prefs(context).getString(KEY_REFRESH, null)
    fun getLogin(context: Context): String? = prefs(context).getString(KEY_LOGIN, null)
    fun getUserId(context: Context): String? = prefs(context).getString(KEY_USER_ID, null)

    fun isLoggedIn(context: Context): Boolean =
        !getAccessToken(context).isNullOrBlank()

    fun logout(context: Context) {
        prefs(context).edit().clear().apply()
    }
}