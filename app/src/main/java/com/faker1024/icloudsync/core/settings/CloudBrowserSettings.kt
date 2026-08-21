package com.faker1024.icloudsync.core.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.cloudBrowserDataStore by preferencesDataStore(name = "cloud_browser_settings")

enum class CloudBrowserLayout { LIST, GRID }
enum class CloudSortField { NAME, MODIFIED_TIME, SIZE, FILE_TYPE }
enum class CloudSortDirection { ASCENDING, DESCENDING }

data class CloudBrowserPreferences(
    val layout: CloudBrowserLayout = CloudBrowserLayout.GRID,
    val iconSize: Float = DEFAULT_ICON_SIZE,
    val sortField: CloudSortField = CloudSortField.NAME,
    val sortDirection: CloudSortDirection = CloudSortDirection.ASCENDING,
    val lastSyncId: String? = null,
    val lastSyncFolderId: String? = null,
    val lastSyncFolderName: String? = null,
    val lastSyncDisplayPath: String? = null,
)

@Singleton
class CloudBrowserSettings @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val layoutKey = stringPreferencesKey("layout")
    private val iconSizeKey = floatPreferencesKey("icon_size")
    private val sortFieldKey = stringPreferencesKey("sort_field")
    private val sortDirectionKey = stringPreferencesKey("sort_direction")
    private val lastSyncIdKey = stringPreferencesKey("last_sync_id")
    private val lastSyncFolderIdKey = stringPreferencesKey("last_sync_folder_id")
    private val lastSyncFolderNameKey = stringPreferencesKey("last_sync_folder_name")
    private val lastSyncDisplayPathKey = stringPreferencesKey("last_sync_display_path")

    val preferences: Flow<CloudBrowserPreferences> = context.cloudBrowserDataStore.data.map { values ->
        CloudBrowserPreferences(
            layout = runCatching {
                CloudBrowserLayout.valueOf(values[layoutKey].orEmpty())
            }.getOrDefault(CloudBrowserLayout.GRID),
            iconSize = clampIconSize(values[iconSizeKey] ?: DEFAULT_ICON_SIZE),
            sortField = enumPreference(values[sortFieldKey], CloudSortField.NAME),
            sortDirection = enumPreference(values[sortDirectionKey], CloudSortDirection.ASCENDING),
            lastSyncId = values[lastSyncIdKey],
            lastSyncFolderId = values[lastSyncFolderIdKey],
            lastSyncFolderName = values[lastSyncFolderNameKey],
            lastSyncDisplayPath = values[lastSyncDisplayPathKey],
        )
    }

    suspend fun setLayout(layout: CloudBrowserLayout) {
        context.cloudBrowserDataStore.edit { it[layoutKey] = layout.name }
    }

    suspend fun setIconSize(value: Float) {
        context.cloudBrowserDataStore.edit { it[iconSizeKey] = clampIconSize(value) }
    }

    suspend fun setSorting(field: CloudSortField, direction: CloudSortDirection) {
        context.cloudBrowserDataStore.edit { values ->
            values[sortFieldKey] = field.name
            values[sortDirectionKey] = direction.name
        }
    }

    suspend fun setLastSync(
        id: String,
        folderId: String,
        folderName: String,
        displayPath: String,
    ) {
        context.cloudBrowserDataStore.edit { values ->
            values[lastSyncIdKey] = id
            values[lastSyncFolderIdKey] = folderId
            values[lastSyncFolderNameKey] = folderName
            values[lastSyncDisplayPathKey] = displayPath
        }
    }
}

private inline fun <reified T : Enum<T>> enumPreference(value: String?, fallback: T): T =
    runCatching { enumValueOf<T>(value.orEmpty()) }.getOrDefault(fallback)

internal fun clampIconSize(value: Float): Float = value.coerceIn(MIN_ICON_SIZE, MAX_ICON_SIZE)

const val MIN_ICON_SIZE = 48f
const val MAX_ICON_SIZE = 144f
const val DEFAULT_ICON_SIZE = 88f
