package com.faker1024.icloudsync.core.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.localBrowserDataStore by preferencesDataStore(name = "local_browser_settings")

enum class LocalBrowserLayout { LIST, GRID }
enum class LocalSortField { NAME, MODIFIED_TIME, SIZE, FILE_TYPE }
enum class LocalSortDirection { ASCENDING, DESCENDING }

data class LocalBrowserPreferences(
    val layout: LocalBrowserLayout = LocalBrowserLayout.GRID,
    val sortField: LocalSortField = LocalSortField.NAME,
    val sortDirection: LocalSortDirection = LocalSortDirection.ASCENDING,
)

@Singleton
class LocalBrowserSettings @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val layoutKey = stringPreferencesKey("layout")
    private val sortFieldKey = stringPreferencesKey("sort_field")
    private val sortDirectionKey = stringPreferencesKey("sort_direction")

    val preferences: Flow<LocalBrowserPreferences> = context.localBrowserDataStore.data.map { values ->
        parseLocalBrowserPreferences(
            layout = values[layoutKey],
            sortField = values[sortFieldKey],
            sortDirection = values[sortDirectionKey],
        )
    }

    suspend fun setLayout(layout: LocalBrowserLayout) {
        context.localBrowserDataStore.edit { it[layoutKey] = layout.name }
    }

    suspend fun setSorting(field: LocalSortField, direction: LocalSortDirection) {
        context.localBrowserDataStore.edit { values ->
            values[sortFieldKey] = field.name
            values[sortDirectionKey] = direction.name
        }
    }
}

internal fun parseLocalBrowserPreferences(
    layout: String?,
    sortField: String?,
    sortDirection: String?,
): LocalBrowserPreferences = LocalBrowserPreferences(
    layout = enumPreference(layout, LocalBrowserLayout.GRID),
    sortField = enumPreference(sortField, LocalSortField.NAME),
    sortDirection = enumPreference(sortDirection, LocalSortDirection.ASCENDING),
)

private inline fun <reified T : Enum<T>> enumPreference(value: String?, fallback: T): T =
    runCatching { enumValueOf<T>(value.orEmpty()) }.getOrDefault(fallback)
