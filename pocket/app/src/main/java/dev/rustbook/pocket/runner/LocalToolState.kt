package dev.rustbook.pocket.runner

/** Availability of the optional on-device Rust toolchain. */
sealed interface LocalToolState {
    /** Not checked yet. */
    data object Unknown : LocalToolState

    /** The APK does not ship a toolchain pack. */
    data object NotPackaged : LocalToolState

    /** Unpacking from assets; [bytesWritten] grows as files land. */
    data class Installing(val bytesWritten: Long) : LocalToolState

    /** Installed and usable. */
    data object Ready : LocalToolState

    /** Present but incomplete, or unpacking failed. */
    data object Failed : LocalToolState
}
