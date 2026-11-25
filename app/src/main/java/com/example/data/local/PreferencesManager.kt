package com.example.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.data.model.VideoQuality
import com.example.localization.AppLanguage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.zaidDataStore: DataStore<Preferences> by preferencesDataStore(name = "zaid_live_tv_prefs")

data class UserPreferences(
    val language: AppLanguage = AppLanguage.ENGLISH,
    val preferredQuality: VideoQuality = VideoQuality.AUTO,
    val logosPreCachedOnce: Boolean = false,
    val initialChannelsUpdated: Boolean = false,
    val lastChannelsUpdatedAt: Long = 0L
)

class PreferencesManager(context: Context) {
    private val dataStore = context.applicationContext.zaidDataStore

    private object Keys {
        val LANGUAGE = stringPreferencesKey("app_language")
        val QUALITY = stringPreferencesKey("preferred_quality")
        val LOGOS_PRECACHED = booleanPreferencesKey("logos_precached")
        val INITIAL_CHANNELS_UPDATED = booleanPreferencesKey("initial_channels_updated")
        val LAST_CHANNELS_UPDATED_AT = longPreferencesKey("last_channels_updated_at")
    }

    val preferencesFlow: Flow<UserPreferences> = dataStore.data.map { prefs ->
        UserPreferences(
            language = AppLanguage.fromCode(prefs[Keys.LANGUAGE]),
            preferredQuality = VideoQuality.fromKey(prefs[Keys.QUALITY]),
            logosPreCachedOnce = prefs[Keys.LOGOS_PRECACHED] ?: false,
            initialChannelsUpdated = prefs[Keys.INITIAL_CHANNELS_UPDATED] ?: false,
            lastChannelsUpdatedAt = prefs[Keys.LAST_CHANNELS_UPDATED_AT] ?: 0L
        )
    }

    suspend fun setLanguage(language: AppLanguage) {
        dataStore.edit { it[Keys.LANGUAGE] = language.code }
    }

    suspend fun setPreferredQuality(quality: VideoQuality) {
        dataStore.edit { it[Keys.QUALITY] = quality.key }
    }

    suspend fun markLogosPreCached() {
        dataStore.edit { it[Keys.LOGOS_PRECACHED] = true }
    }

    suspend fun markInitialChannelsUpdated(timestamp: Long = System.currentTimeMillis()) {
        dataStore.edit {
            it[Keys.INITIAL_CHANNELS_UPDATED] = true
            it[Keys.LOGOS_PRECACHED] = true
            it[Keys.LAST_CHANNELS_UPDATED_AT] = timestamp
        }
    }
}
