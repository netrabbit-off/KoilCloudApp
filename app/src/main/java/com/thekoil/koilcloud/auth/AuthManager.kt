package com.thekoil.koilcloud.auth

import android.content.Context

object AuthManager {
    private const val PREFS = "koilcloud_auth"
    private const val KEY_TOKEN = "token"
    private const val KEY_LOGIN = "login"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun save(context: Context, token: String, login: String) {
        prefs(context).edit()
            .putString(KEY_TOKEN, token)
            .putString(KEY_LOGIN, login)
            .apply()
    }

    fun getToken(context: Context): String? =
        prefs(context).getString(KEY_TOKEN, null)

    fun getLogin(context: Context): String? =
        prefs(context).getString(KEY_LOGIN, null)

    fun isLoggedIn(context: Context): Boolean =
        !getToken(context).isNullOrBlank()

    fun logout(context: Context) {
        prefs(context).edit().clear().apply()
    }
}