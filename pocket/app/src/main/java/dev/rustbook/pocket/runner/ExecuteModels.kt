package dev.rustbook.pocket.runner

import org.json.JSONObject

/**
 * The subset of the Rust Playground request that this app understands.
 *
 * The book's playground code posts this shape directly, so parsing it here
 * keeps the web layer unchanged: whatever mdBook asks for is forwarded to the
 * selected executor, and the result is handed back in the same JSON shape.
 */
data class PlaygroundRequest(
    val code: String,
    val channel: String = "stable",
    val mode: String = "debug",
    val edition: String = "2024",
    val crateType: String = "bin",
    val tests: Boolean = false,
    val backtrace: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("code", code)
        put("channel", channel)
        put("mode", mode)
        put("edition", edition)
        put("crateType", crateType)
        put("tests", tests)
        put("backtrace", backtrace)
    }

    fun codeSizeBytes(): Int = code.toByteArray(Charsets.UTF_8).size

    companion object {
        const val MAX_CODE_BYTES = 64 * 1024

        /**
         * Parses the body that mdBook posts to the playground.
         *
         * mdBook 0.5 still posts to the legacy `evaluate.json` endpoint, whose
         * field names differ (`version`, `optimize`), while newer clients use
         * the `/execute` shape. Both are accepted here.
         */
        fun fromBookPayload(payload: String): PlaygroundRequest? {
            val json = runCatching { JSONObject(payload) }.getOrNull() ?: return null

            val code = json.opt("code")
            if (code !is String || code.isBlank()) {
                return null
            }

            val channel = json.optString("channel")
                .ifBlank { json.optString("version") }
                .ifBlank { "stable" }

            val mode = json.optString("mode").ifBlank {
                // `optimize` is "0" or "1" in the legacy payload.
                if (json.optString("optimize", "0") == "0") "debug" else "release"
            }

            return PlaygroundRequest(
                code = code,
                channel = channel,
                mode = mode,
                edition = json.optString("edition", "2024").ifBlank { "2024" },
                crateType = json.optString("crateType", "bin").ifBlank { "bin" },
                tests = json.optBoolean("tests", false),
                backtrace = json.optBoolean("backtrace", false),
            )
        }
    }
}

/** Why a run could not produce a usable result. */
enum class ExecFailure {
    OFFLINE,
    RATE_LIMITED,
    TIMEOUT,
    SERVER_ERROR,
    INVALID_RESPONSE,
    TOO_LARGE,
    UNSUPPORTED,
    LOCAL_UNAVAILABLE,
}

/**
 * The response shape the Rust Playground uses. mdBook's frontend renders
 * `stdout`/`stderr` and treats `success` as the pass/fail signal, so keeping
 * this identical means the book UI needs no knowledge of the native layer.
 */
data class ExecResult(
    val success: Boolean,
    val stdout: String = "",
    val stderr: String = "",
    val exitDetail: String = "",
    val failure: ExecFailure? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("success", success)
        put("stdout", stdout)
        put("stderr", stderr)
        put("exitDetail", exitDetail)
    }

    companion object {
        fun success(stdout: String = "", stderr: String = "") = ExecResult(
            success = true,
            stdout = stdout,
            stderr = stderr,
            exitDetail = "success",
        )

        fun failed(reason: ExecFailure, detail: String = "") = ExecResult(
            success = false,
            stderr = detail,
            exitDetail = reason.name,
            failure = reason,
        )

        fun parse(body: String): ExecResult? {
            val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
            if (!json.has("success")) {
                return null
            }
            return ExecResult(
                success = json.optBoolean("success", false),
                stdout = json.optString("stdout", ""),
                stderr = json.optString("stderr", ""),
                exitDetail = json.optString("exitDetail", ""),
            )
        }
    }
}
