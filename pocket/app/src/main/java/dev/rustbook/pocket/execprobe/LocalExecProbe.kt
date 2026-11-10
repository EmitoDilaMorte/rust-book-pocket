package dev.rustbook.pocket.execprobe

import android.content.Context
import android.util.Log
import dev.rustbook.pocket.runner.LocalExecutor
import dev.rustbook.pocket.runner.LocalToolchain
import dev.rustbook.pocket.runner.LocalToolchainInstaller
import dev.rustbook.pocket.runner.PlaygroundRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Debug-only smoke test for on-device compilation: unpacks the toolchain if
 * needed, compiles a snippet, runs it and logs the result.
 *
 *   adb shell am start -n <pkg>/dev.rustbook.pocket.MainActivity \
 *       --ez localexec true
 */
object LocalExecProbe {

    private const val TAG = "RustBookPocket"

    private val snippet = """
        fn main() {
            let mut values: Vec<i32> = (1..=10).rev().collect();
            values.sort();
            let total: i32 = values.iter().sum();
            println!("local total={total}");
        }
    """.trimIndent()

    private val brokenSnippet = """
        fn main() {
            let x = 1;
            x = 2;
        }
    """.trimIndent()

    suspend fun run(context: Context) = withContext(Dispatchers.IO) {
        val toolchain = LocalToolchain(context)
        val installer = LocalToolchainInstaller(context, toolchain)

        val started = System.currentTimeMillis()
        val install = installer.ensureInstalled()
        Log.i(TAG, "localexec installed=${install.isSuccess} in_ms=${System.currentTimeMillis() - started}")
        Log.i(TAG, "localexec complete=${toolchain.isComplete} version=${toolchain.installedVersion}")

        if (install.isFailure) {
            Log.i(TAG, "localexec install failed: ${install.exceptionOrNull()?.message}")
            return@withContext
        }

        val executor = LocalExecutor(toolchain)
        Log.i(TAG, "localexec available=${executor.isAvailable()}")

        val goodStart = System.currentTimeMillis()
        val result = executor.execute(PlaygroundRequest(code = snippet, edition = "2024"))
        val elapsed = System.currentTimeMillis() - goodStart
        Log.i(
            TAG,
            "localexec success=${result.success} ms=$elapsed stdout=${result.stdout.trim()} " +
                "detail=${result.exitDetail}",
        )
        if (!result.success) {
            result.stderr.lineSequence().take(40).forEach { line ->
                Log.i(TAG, "localexec stderr| $line")
            }
        }

        val broken = executor.execute(PlaygroundRequest(code = brokenSnippet, edition = "2024"))
        Log.i(
            TAG,
            "localexec broken success=${broken.success} stderr=${broken.stderr.trim().take(160)}",
        )
    }
}
