package dev.rustbook.pocket.runner

import android.content.Context
import java.io.File

/**
 * Layout of the optional on-device Rust toolchain.
 *
 * Unlike a normal modern app, this one targets SDK 28, which keeps the legacy
 * behaviour of allowing `execve` on files the app itself wrote. That single
 * decision removes three problems at once:
 *
 *  - the toolchain can be unpacked from assets into an ordinary directory,
 *    keeping its original file names and SONAMEs,
 *  - the compiler can be executed directly, and
 *  - the binaries it produces can be run without a memfd helper.
 *
 * Termux makes the same trade-off. See `pocket/docs/local-execution.md`.
 */
class LocalToolchain(context: Context) {

    /** Root of the unpacked toolchain. */
    val root: File = File(context.filesDir, "toolchain")

    val rustc: File = File(root, "bin/rustc")
    val clang: File = File(root, "bin/clang")

    /** The directory passed to `rustc --sysroot`. */
    val sysroot: File = root

    /** Where compile jobs and their outputs are written. */
    val workDir: File = File(context.cacheDir, "rust-build")

    private val marker: File = File(root, ".version")

    /** Version recorded by the packaging script, or `null` when absent. */
    val installedVersion: String?
        get() = runCatching { marker.readText().trim() }.getOrNull()?.ifBlank { null }

    private val stdLibDir: File
        get() = File(root, "lib/rustlib/aarch64-linux-android/lib")

    fun stdRlib(): File? = stdLibDir
        .takeIf { it.isDirectory }
        ?.listFiles { file -> file.name.startsWith("libstd-") && file.extension == "rlib" }
        ?.firstOrNull()

    val isComplete: Boolean
        get() = rustc.canExecute() && clang.canExecute() && stdRlib() != null

    fun needsInstall(expectedVersion: String): Boolean =
        installedVersion != expectedVersion || !isComplete

    fun unavailableReason(): String? = when {
        !rustc.exists() -> "The Rust toolchain has not been installed yet."
        !clang.exists() -> "The linker is missing from the toolchain."
        stdRlib() == null -> "The Rust standard library is missing."
        !rustc.canExecute() -> "The toolchain files are not executable."
        else -> null
    }

    fun prepareDirectories() {
        workDir.mkdirs()
    }

    companion object {
        /**
         * Version of the packaged toolchain. Bump it when repackaging so
         * existing installs refresh themselves.
         */
        const val VERSION = "rust-1.99.1-android-2026-11.4"
    }
}
