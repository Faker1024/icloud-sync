package com.faker1024.icloudsync.core.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.importSettingsDataStore by preferencesDataStore(name = "import_settings")

@Singleton
class ImportSettings @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val albumNameKey = stringPreferencesKey("album_name")

    val albumName: Flow<String> = context.importSettingsDataStore.data.map { preferences ->
        preferences[albumNameKey] ?: DEFAULT_ALBUM_NAME
    }

    suspend fun currentAlbumName(): String = albumName.map(::sanitizeAlbumName).first()

    suspend fun setAlbumName(value: String) {
        context.importSettingsDataStore.edit { preferences ->
            preferences[albumNameKey] = sanitizeAlbumName(value)
        }
    }

    companion object {
        const val DEFAULT_ALBUM_NAME = "iCloud Photos"

        fun sanitizeAlbumName(value: String): String {
            val sanitized = value
                .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ")
                .trim()
                .replace(Regex("\\s+"), " ")
                .take(64)
            return sanitized.ifBlank { DEFAULT_ALBUM_NAME }
        }
    }
}
