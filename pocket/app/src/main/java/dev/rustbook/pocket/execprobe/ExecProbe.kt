package dev.rustbook.pocket.execprobe

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Debug-only experiment: can this app execute code that it wrote itself?
 *
 * Android 10+ blocks `execve()` on files inside the app's data directory, so
 * the on-device Rust toolchain needs a different route (a native library in
 * `nativeLibraryDir`, or the `pocket-exec` memfd helper). This probe records
 * which routes actually work on a given device.
 *
 * The binaries live in `src/debug/assets/execprobe/`, so release builds simply
 * report the copy step as failed.
 */
object ExecProbe {

    private const val TAG = "RustBookPocket"
    private const val ASSET_DIR = "execprobe"

    data class Outcome(
        val step: String,
        val success: Boolean,
        val detail: String,
    )

    suspend fun run(context: Context): List<Outcome> = withContext(Dispatchers.IO) {
        val results = mutableListOf<Outcome>()
        val dir = File(context.filesDir, "execprobe").apply { mkdirs() }

        for (name in listOf("hello", "pocket-exec")) {
            val copied = runCatching {
                val target = File(dir, name)
                context.assets.open("$ASSET_DIR/$name").use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                target.setExecutable(true, true)
                target
            }.getOrElse { error ->
                results += Outcome("copy $name", false, error.message.orEmpty())
                null
            }
            if (copied != null) {
                results += Outcome("copy $name", true, copied.absolutePath)
            }
        }

        val hello = File(dir, "hello")
        val helper = File(dir, "pocket-exec")

        if (hello.exists()) {
            results += exec(listOf(hello.absolutePath), "direct exec from filesDir")
            if (helper.exists()) {
                results += exec(
                    listOf(helper.absolutePath, hello.absolutePath),
                    "exec through memfd helper",
                )
            }
        }

        // Route used by the real toolchain: everything executable ships in the
        // APK's native libraries, which Android extracts with the exec bit set.
        val nativeDir = context.applicationInfo.nativeLibraryDir
        val nativeProbe = File(nativeDir, "libpocketprobe.so")
        val nativeHelper = File(nativeDir, "libpocketexec.so")

        if (nativeProbe.exists()) {
            results += exec(listOf(nativeProbe.absolutePath), "direct exec from nativeLibraryDir")
        } else {
            results += Outcome("direct exec from nativeLibraryDir", false, "not packaged")
        }

        if (nativeHelper.exists() && hello.exists()) {
            results += exec(
                listOf(nativeHelper.absolutePath, hello.absolutePath),
                "exec filesDir binary through native memfd helper",
            )
        }

        results
    }

    private fun exec(command: List<String>, step: String): Outcome {
        return runCatching {
            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText().trim()
            val exit = process.waitFor()
            Outcome(step, exit == 0, "exit=$exit ${output.take(200)}")
        }.getOrElse { error ->
            Outcome(step, false, "${error::class.java.simpleName}: ${error.message}")
        }
    }

    fun report(results: List<Outcome>) {
        results.forEach { outcome ->
            val status = if (outcome.success) "OK" else "FAIL"
            Log.i(TAG, "execprobe $status ${outcome.step}: ${outcome.detail}")
        }
    }
}
