package com.salmansaadiq.dheu.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.salmansaadiq.dheu.ui.theme.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settings by preferencesDataStore(name = "settings")

/** Light / dark / system, chosen on this device (like the website's theme picker). */
object ThemePrefs {
    private val KEY = stringPreferencesKey("theme")

    fun flow(context: Context): Flow<ThemeMode> =
        context.settings.data.map { prefs -> ThemeMode.entries.firstOrNull { it.name == prefs[KEY] } ?: ThemeMode.System }

    suspend fun set(context: Context, mode: ThemeMode) {
        context.settings.edit { it[KEY] = mode.name }
    }
}

/** "Suggested for you" hidden with its ✕: for a day, as on the website. */
object SuggestionPrefs {
    private val KEY = longPreferencesKey("suggestions_dismissed_at")
    private const val HIDE_FOR_MS = 24 * 60 * 60 * 1000L

    fun hidden(context: Context): Flow<Boolean> =
        context.settings.data.map { prefs -> System.currentTimeMillis() - (prefs[KEY] ?: 0L) < HIDE_FOR_MS }

    suspend fun dismiss(context: Context) {
        context.settings.edit { it[KEY] = System.currentTimeMillis() }
    }
}

/**
 * The "new version" banner: when GitHub was last asked, the latest release it
 * named (so the banner stays up between checks), and the version whose banner
 * was closed.
 */
object UpdatePrefs {
    private val CHECKED_AT = longPreferencesKey("update_checked_at")
    private val LATEST_VERSION = stringPreferencesKey("update_latest_version")
    private val LATEST_URL = stringPreferencesKey("update_latest_url")
    private val DISMISSED = stringPreferencesKey("update_dismissed_version")

    suspend fun checkedAt(context: Context): Long = context.settings.data.first()[CHECKED_AT] ?: 0L

    suspend fun saveLatest(context: Context, version: String, url: String) {
        context.settings.edit {
            it[CHECKED_AT] = System.currentTimeMillis()
            it[LATEST_VERSION] = version
            it[LATEST_URL] = url
        }
    }

    suspend fun latest(context: Context): Pair<String, String>? {
        val prefs = context.settings.data.first()
        val version = prefs[LATEST_VERSION] ?: return null
        val url = prefs[LATEST_URL] ?: return null
        return version to url
    }

    suspend fun dismissed(context: Context): String? = context.settings.data.first()[DISMISSED]

    suspend fun dismiss(context: Context, version: String) {
        context.settings.edit { it[DISMISSED] = version }
    }
}

/** Push notifications on this phone: on unless switched off in Settings; the permission is asked for once. */
object PushPrefs {
    private val ENABLED = booleanPreferencesKey("push_enabled")
    private val ASKED = booleanPreferencesKey("push_permission_asked")

    fun enabled(context: Context): Flow<Boolean> = context.settings.data.map { it[ENABLED] ?: true }

    suspend fun enabledNow(context: Context): Boolean = enabled(context).first()

    suspend fun setEnabled(context: Context, on: Boolean) {
        context.settings.edit { it[ENABLED] = on }
    }

    /** True the first time only: the app asks for the notification permission once, after signing in. */
    suspend fun firstAsk(context: Context): Boolean {
        if (context.settings.data.first()[ASKED] == true) return false
        context.settings.edit { it[ASKED] = true }
        return true
    }
}
