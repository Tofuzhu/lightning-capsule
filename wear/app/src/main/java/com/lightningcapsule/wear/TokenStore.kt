package com.lightningcapsule.wear

import android.content.Context
import android.content.SharedPreferences

/**
 * Minimal wrapper around SharedPreferences for the API auth token.
 *
 * The token value is never written to logs or crash reports.
 */
class TokenStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var token: String?
        get() = prefs.getString(KEY_TOKEN, null)?.takeIf { it.isNotBlank() }
        set(value) {
            prefs.edit().apply {
                if (value.isNullOrBlank()) remove(KEY_TOKEN) else putString(KEY_TOKEN, value.trim())
            }.apply()
        }

    val hasToken: Boolean
        get() = token != null

    private companion object {
        const val PREFS_NAME = "lightning_capsule_prefs"
        const val KEY_TOKEN = "auth_token"
    }
}
