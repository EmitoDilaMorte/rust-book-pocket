package dev.rustbook.pocket.runner

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Compiles and runs a snippet with the toolchain unpacked from the APK.
 *
 * Verified on a Snapdragon phone (Android 16, 12 GB RAM): a std-only snippet
 * compiles in 0.37–0.46 s and produces a ~570 KB binary. See
 * `pocket/docs/local-execution.md` for the measurements and the layout.
 */
class LocalExecutor(
    private val toolchain: LocalToolchain,
    private val timeoutSeconds: Long = 30,
) : RustExecutor {

    override val id: String = "local"

    override fun isAvailable(): Boolean = toolchain.isComplete

    override suspend fun execute(request: PlaygroundRequest): ExecResult = withContext(Dispatchers.IO) {
        val reason = toolchain.unavailableReason()
        if (reason != null) {
            return@withContext ExecResult.failed(ExecFailure.LOCAL_UNAVAILABLE, reason)
        }

        if (CodeHeuristics.requiresExternalCrates(request.code)) {
            return@withContext ExecResult.failed(
                ExecFailure.UNSUPPORTED,
                "This snippet needs a crate that is only available on the Rust Playground.",
            )
        }

        toolchain.prepareDirectories()

        val job = File(toolchain.workDir, "job-${System.nanoTime()}").apply { mkdirs() }
        val source = File(job, "main.rs").apply { writeText(request.code) }
        val binary = File(job, "program")

        try {
            val compile = run(
                command = buildList {
                    add(toolchain.rustc.absolutePath)
                    add("--edition")
                    add(request.edition)
                    add("--crate-type")
                    add("bin")
                    add("--sysroot")
                    add(toolchain.sysroot.absolutePath)
                    add("-C")
                    add("linker=${toolchain.clang.absolutePath}")
                    add("-C")
                    add("link-arg=-L${toolchain.root.absolutePath}/lib")
                    if (request.mode == "release") add("-O")
                    add(source.absolutePath)
                    add("-o")
                    add(binary.absolutePath)
                },
                workingDir = job,
            )

            if (compile.timedOut) {
                return@withContext ExecResult.failed(ExecFailure.TIMEOUT)
            }

            if (!binary.exists()) {
                return@withContext ExecResult(
                    success = false,
                    stdout = compile.stdout,
                    stderr = compile.stderr.replace("${job.absolutePath}/", ""),
                    exitDetail = "Compilation failed",
                )
            }

            val execution = run(listOf(binary.absolutePath), workingDir = job)

            when {
                execution.timedOut -> ExecResult.failed(ExecFailure.TIMEOUT)
                execution.exitCode == 0 -> ExecResult(
                    success = true,
                    stdout = execution.stdout,
                    stderr = execution.stderr.replace("${job.absolutePath}/", ""),
                    exitDetail = "Exited with status 0",
                )

                else -> ExecResult(
                    success = false,
                    stdout = execution.stdout,
                    stderr = execution.stderr.replace("${job.absolutePath}/", ""),
                    exitDetail = "Exited with status ${execution.exitCode}",
                )
            }
        } finally {
            job.deleteRecursively()
        }
    }

    private data class ProcessOutput(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val timedOut: Boolean,
    )

    private fun run(command: List<String>, workingDir: File): ProcessOutput {
        val builder = ProcessBuilder(command)
            .directory(workingDir)
            .redirectErrorStream(false)

        builder.environment().apply {
            // rustc resolves its driver and LLVM from here.
            put(
                "LD_LIBRARY_PATH",
                "${toolchain.root.absolutePath}/lib:" +
                    "${toolchain.root.absolutePath}/lib/rustlib/aarch64-linux-android/lib",
            )
            put("TMPDIR", workingDir.absolutePath)
            put("HOME", workingDir.absolutePath)
            put("RUST_BACKTRACE", "0")
        }

        val process = builder.start()

        // Drain both pipes while the process runs so a chatty compiler cannot
        // fill the OS buffer and block.
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val stdoutThread = Thread { process.inputStream.bufferedReader().copyTo(StringBuilderWriter(stdout)) }
        val stderrThread = Thread { process.errorStream.bufferedReader().copyTo(StringBuilderWriter(stderr)) }
        stdoutThread.start()
        stderrThread.start()

        val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)

        if (!finished) {
            process.destroyForcibly()
            process.waitFor(2, TimeUnit.SECONDS)
            stdoutThread.join(1000)
            stderrThread.join(1000)
            return ProcessOutput(-1, stdout.toString(), stderr.toString(), timedOut = true)
        }

        stdoutThread.join(2000)
        stderrThread.join(2000)

        return ProcessOutput(
            exitCode = process.exitValue(),
            stdout = stdout.toString(),
            stderr = stderr.toString(),
            timedOut = false,
        )
    }

    /** Minimal `Writer` adapter so buffers can be filled from a thread. */
    private class StringBuilderWriter(private val builder: StringBuilder) : java.io.Writer() {
        override fun write(cbuf: CharArray, off: Int, len: Int) {
            builder.appendRange(cbuf, off, off + len)
        }

        override fun flush() = Unit
        override fun close() = Unit
    }
}
