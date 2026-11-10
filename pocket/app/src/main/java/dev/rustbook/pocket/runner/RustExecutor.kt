package dev.rustbook.pocket.runner

/**
 * A way to compile and run a Rust snippet.
 *
 * Implementations exist for the public playground (or a self-hosted one) and,
 * later, for a toolchain bundled with the app. Keeping this interface small
 * means the WebView bridge never needs to know which one answered.
 */
interface RustExecutor {
    val id: String

    /** Cheap check used by the router and the settings screen. */
    fun isAvailable(): Boolean = true

    suspend fun execute(request: PlaygroundRequest): ExecResult
}
