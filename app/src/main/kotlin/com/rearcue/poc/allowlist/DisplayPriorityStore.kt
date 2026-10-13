package com.rearcue.poc.allowlist

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.rearcue.poc.core.DisplayPriority
import kotlinx.coroutines.flow.first

private val Context.displayPriorityStore by preferencesDataStore(name = "display_priority")

data class DisplayPrioritySettings(
    val apps: Set<String> = emptySet(),
    val order: List<DisplayPriority> = DisplayPriority.DEFAULT,
)

object DisplayPriorityStore {
    private val appsKey = stringSetPreferencesKey("apps")
    private val orderKey = stringPreferencesKey("order")

    suspend fun load(context: Context): DisplayPrioritySettings {
        val prefs = context.displayPriorityStore.data.first()
        val order = prefs[orderKey].orEmpty().split(',').mapNotNull { name ->
            DisplayPriority.entries.firstOrNull { it.name == name }
        }
        return DisplayPrioritySettings(prefs[appsKey].orEmpty(), DisplayPriority.normalize(order))
    }

    suspend fun save(context: Context, settings: DisplayPrioritySettings) {
        context.displayPriorityStore.edit {
            it[appsKey] = settings.apps
            it[orderKey] = DisplayPriority.normalize(settings.order).joinToString(",") { priority -> priority.name }
        }
    }
}
