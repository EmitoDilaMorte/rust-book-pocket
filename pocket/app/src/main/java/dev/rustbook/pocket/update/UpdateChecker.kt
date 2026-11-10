package dev.rustbook.pocket.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data object Unavailable : UpdateState
    data object Error : UpdateState
    data class Available(val version: String, val url: String) : UpdateState
}

/**
 * Checks the project's GitHub Releases for a newer build.
 *
 * There is no store listing and no auto-update: the app only compares tags and
 * offers to open the release page in the browser.
 */
class UpdateChecker(
    private val client: OkHttpClient,
    private val repository: String,
    private val currentVersion: String,
) {

    val isConfigured: Boolean = repository.isNotBlank()

    suspend fun check(): UpdateState = withContext(Dispatchers.IO) {
        if (!isConfigured) {
            return@withContext UpdateState.Unavailable
        }

        val request = Request.Builder()
            .url("https://api.github.com/repos/$repository/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Rust-Book-Pocket")
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext UpdateState.Error
                }

                val body = response.body?.string().orEmpty()
                val json = runCatching { JSONObject(body) }.getOrNull()
                    ?: return@withContext UpdateState.Error

                val tag = json.optString("tag_name").ifBlank {
                    return@withContext UpdateState.Error
                }
                val htmlUrl = json.optString("html_url")

                if (isNewer(tag, currentVersion)) {
                    UpdateState.Available(version = tag, url = htmlUrl)
                } else {
                    UpdateState.UpToDate
                }
            }
        } catch (io: IOException) {
            UpdateState.Error
        }
    }

    internal companion object {
        private val VERSION_PREFIXES = listOf("pocket-v", "pocket-", "v")

        fun isNewer(candidate: String, current: String): Boolean {
            val left = parse(candidate)
            val right = parse(current)
            val size = maxOf(left.size, right.size)

            for (index in 0 until size) {
                val a = left.getOrElse(index) { 0 }
                val b = right.getOrElse(index) { 0 }
                if (a != b) {
                    return a > b
                }
            }
            return false
        }

        private fun parse(value: String): List<Int> {
            var cleaned = value.trim()
            VERSION_PREFIXES.firstOrNull { cleaned.startsWith(it) }?.let {
                cleaned = cleaned.removePrefix(it)
            }
            return cleaned
                .substringBefore('+')
                .substringBefore('-')
                .split('.')
                .map { part -> part.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        }
    }
}
