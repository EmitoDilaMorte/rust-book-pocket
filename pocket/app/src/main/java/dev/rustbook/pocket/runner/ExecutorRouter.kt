package dev.rustbook.pocket.runner

/** How the app decides where to run a snippet. */
enum class ExecutionMode {
    /** Prefer the on-device toolchain, fall back to the playground. */
    AUTO,

    /** Always use the configured playground server. */
    REMOTE,

    /** Always use the on-device toolchain. */
    LOCAL;

    companion object {
        fun fromStorage(value: String?): ExecutionMode =
            entries.firstOrNull { it.name == value } ?: AUTO
    }
}

/**
 * Picks an executor for each snippet.
 *
 * Resolution rules:
 * - `REMOTE` always uses the network executor.
 * - `LOCAL` never silently leaves the device; if the toolchain is missing the
 *   reader sees an explicit error instead of a surprise upload.
 * - `AUTO` uses the local toolchain when it is installed and the snippet does
 *   not need crates that only the playground provides, then falls back.
 */
class ExecutorRouter(
    private val remote: RustExecutor,
    private val local: RustExecutor?,
    private val mode: () -> ExecutionMode,
    private val isOnline: () -> Boolean,
) : RustExecutor {

    override val id: String = "router"

    override fun isAvailable(): Boolean = remote.isAvailable() || local?.isAvailable() == true

    override suspend fun execute(request: PlaygroundRequest): ExecResult {
        if (request.codeSizeBytes() > PlaygroundRequest.MAX_CODE_BYTES) {
            return ExecResult.failed(ExecFailure.TOO_LARGE)
        }

        val localExecutor = local
        val localUsable = localExecutor?.isAvailable() == true &&
            !CodeHeuristics.requiresExternalCrates(request.code)

        val preferLocal = when (mode()) {
            ExecutionMode.LOCAL -> true
            ExecutionMode.REMOTE -> false
            ExecutionMode.AUTO -> localUsable
        }

        if (preferLocal) {
            if (localUsable && localExecutor != null) {
                return localExecutor.execute(request)
            }
            if (mode() == ExecutionMode.LOCAL) {
                return ExecResult.failed(ExecFailure.LOCAL_UNAVAILABLE)
            }
        }

        if (!isOnline()) {
            return ExecResult.failed(ExecFailure.OFFLINE)
        }

        return remote.execute(request)
    }
}
