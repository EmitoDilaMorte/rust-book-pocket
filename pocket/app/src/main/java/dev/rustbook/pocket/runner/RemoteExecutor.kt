package dev.rustbook.pocket.runner

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException

/**
 * Runs snippets through a Rust Playground server.
 *
 * The public instance has no SLA and no documented API guarantees, so every
 * failure is mapped to a typed [ExecFailure] and surfaced as readable text
 * instead of a stack trace.
 */
class RemoteExecutor(
    private val client: OkHttpClient,
    private val baseUrlProvider: () -> String,
) : RustExecutor {

    override val id: String = "remote"

    override suspend fun execute(request: PlaygroundRequest): ExecResult = withContext(Dispatchers.IO) {
        if (request.codeSizeBytes() > PlaygroundRequest.MAX_CODE_BYTES) {
            return@withContext ExecResult.failed(ExecFailure.TOO_LARGE)
        }

        val baseUrl = baseUrlProvider().trim().trimEnd('/').ifBlank { DEFAULT_BASE_URL }
        val httpRequest = Request.Builder()
            .url("$baseUrl/execute")
            .header("Accept", "application/json")
            .post(request.toJson().toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        try {
            client.newCall(httpRequest).execute().use { response ->
                val body = response.body?.string().orEmpty()
                when {
                    response.code == 429 -> ExecResult.failed(ExecFailure.RATE_LIMITED)
                    response.code >= 500 -> ExecResult.failed(
                        ExecFailure.SERVER_ERROR,
                        "Playground returned HTTP ${response.code}",
                    )

                    !response.isSuccessful -> ExecResult.failed(
                        ExecFailure.INVALID_RESPONSE,
                        "Playground returned HTTP ${response.code}",
                    )

                    else -> ExecResult.parse(body)
                        ?: ExecResult.failed(ExecFailure.INVALID_RESPONSE, "Unexpected playground response")
                }
            }
        } catch (timeout: SocketTimeoutException) {
            ExecResult.failed(ExecFailure.TIMEOUT)
        } catch (interrupted: InterruptedIOException) {
            ExecResult.failed(ExecFailure.TIMEOUT)
        } catch (io: IOException) {
            ExecResult.failed(ExecFailure.OFFLINE, io.message.orEmpty())
        }
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://play.rust-lang.org"

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
