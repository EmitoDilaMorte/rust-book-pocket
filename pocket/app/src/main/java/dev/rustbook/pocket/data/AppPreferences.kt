package dev.rustbook.pocket.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.rustbook.pocket.runner.ExecutionMode
import dev.rustbook.pocket.runner.RemoteExecutor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONObject

private val Context.pocketDataStore: DataStore<Preferences> by preferencesDataStore(name = "pocket_preferences")

data class ReaderSettings(
    val executionMode: ExecutionMode = ExecutionMode.AUTO,
    val playgroundUrl: String = RemoteExecutor.DEFAULT_BASE_URL,
    val textZoom: Int = DEFAULT_TEXT_ZOOM,
) {
    companion object {
        const val MIN_TEXT_ZOOM = 80
        const val MAX_TEXT_ZOOM = 150
        const val DEFAULT_TEXT_ZOOM = 100
    }
}

data class ReadingPosition(
    val path: String,
    val progress: Float,
)

/**
 * Local-only preferences and reading progress.
 *
 * There is no account and no sync: everything lives in the app's private
 * DataStore. Progress is kept as a small JSON map keyed by the chapter's path
 * inside the bundled book (for example `ch04-01-what-is-ownership.html`).
 */
class AppPreferences(context: Context) {

    private val dataStore = context.applicationContext.pocketDataStore

    val settings: Flow<ReaderSettings> = dataStore.data.map { prefs ->
        ReaderSettings(
            executionMode = ExecutionMode.fromStorage(prefs[KEY_EXECUTION_MODE]),
            playgroundUrl = prefs[KEY_PLAYGROUND_URL] ?: RemoteExecutor.DEFAULT_BASE_URL,
            textZoom = (prefs[KEY_TEXT_ZOOM] ?: ReaderSettings.DEFAULT_TEXT_ZOOM)
                .coerceIn(ReaderSettings.MIN_TEXT_ZOOM, ReaderSettings.MAX_TEXT_ZOOM),
        )
    }

    suspend fun setExecutionMode(mode: ExecutionMode) {
        dataStore.edit { it[KEY_EXECUTION_MODE] = mode.name }
    }

    suspend fun setPlaygroundUrl(url: String) {
        dataStore.edit { it[KEY_PLAYGROUND_URL] = url.trim() }
    }

    suspend fun setTextZoom(percent: Int) {
        dataStore.edit {
            it[KEY_TEXT_ZOOM] = percent.coerceIn(
                ReaderSettings.MIN_TEXT_ZOOM,
                ReaderSettings.MAX_TEXT_ZOOM,
            )
        }
    }

    suspend fun recordProgress(path: String, progress: Float) {
        if (path.isBlank()) return
        dataStore.edit { prefs ->
            val map = decodeProgress(prefs[KEY_PROGRESS])
            map[path] = progress.coerceIn(0f, 1f)

            if (map.size > MAX_PROGRESS_ENTRIES) {
                val keep = map.entries.sortedByDescending { it.value }.take(MAX_PROGRESS_ENTRIES)
                map.clear()
                keep.forEach { (key, value) -> map[key] = value }
            }

            prefs[KEY_PROGRESS] = encodeProgress(map)
            prefs[KEY_LAST_PATH] = path
        }
    }

    suspend fun readingPosition(): ReadingPosition? {
        val prefs = dataStore.data.first()
        val path = prefs[KEY_LAST_PATH] ?: return null
        val progress = decodeProgress(prefs[KEY_PROGRESS])[path] ?: 0f
        return ReadingPosition(path, progress)
    }

    suspend fun clearProgress() {
        dataStore.edit { prefs ->
            prefs.remove(KEY_PROGRESS)
            prefs.remove(KEY_LAST_PATH)
        }
    }

    private fun decodeProgress(raw: String?): MutableMap<String, Float> {
        if (raw.isNullOrBlank()) return mutableMapOf()
        return runCatching {
            val json = JSONObject(raw)
            buildMap {
                json.keys().forEach { key ->
                    put(key, json.optDouble(key, 0.0).toFloat())
                }
            }.toMutableMap()
        }.getOrDefault(mutableMapOf())
    }

    private fun encodeProgress(map: Map<String, Float>): String {
        val json = JSONObject()
        map.forEach { (key, value) -> json.put(key, value.toDouble()) }
        return json.toString()
    }

    private companion object {
        val KEY_EXECUTION_MODE = stringPreferencesKey("execution_mode")
        val KEY_PLAYGROUND_URL = stringPreferencesKey("playground_url")
        val KEY_TEXT_ZOOM = intPreferencesKey("text_zoom")
        val KEY_LAST_PATH = stringPreferencesKey("last_path")
        val KEY_PROGRESS = stringPreferencesKey("reading_progress")

        const val MAX_PROGRESS_ENTRIES = 300
    }
}
