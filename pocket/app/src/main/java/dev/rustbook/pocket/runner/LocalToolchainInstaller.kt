package dev.rustbook.pocket.runner

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Unpacks the bundled toolchain from assets into [LocalToolchain.root].
 *
 * The archive is stored uncompressed inside the APK (`noCompress` in Gradle),
 * so unpacking is mostly a stream copy. It happens once per toolchain version
 * and is skipped when the installed marker already matches.
 */
class LocalToolchainInstaller(
    private val context: Context,
    private val toolchain: LocalToolchain,
) {

    private val archiveName = "toolchain/toolchain.zip"

    data class Progress(val bytesWritten: Long, val entries: Int)

    suspend fun ensureInstalled(
        onProgress: (Progress) -> Unit = {},
    ): Result<Unit> = withContext(Dispatchers.IO) {
        if (!toolchain.needsInstall(LocalToolchain.VERSION)) {
            return@withContext Result.success(Unit)
        }

        runCatching {
            val root = toolchain.root
            if (root.exists()) {
                root.deleteRecursively()
            }
            root.mkdirs()

            var written = 0L
            var entries = 0

            context.assets.open(archiveName).use { raw ->
                ZipInputStream(raw.buffered()).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        val target = File(root, entry.name)

                        // Guard against path traversal in the archive.
                        if (!target.canonicalPath.startsWith(root.canonicalPath + File.separator)) {
                            zip.closeEntry()
                            continue
                        }

                        if (entry.isDirectory) {
                            target.mkdirs()
                            zip.closeEntry()
                            continue
                        }

                        target.parentFile?.mkdirs()
                        target.outputStream().buffered().use { output ->
                            val copied = zip.copyTo(output)
                            written += copied
                        }

                        // Executables and shared libraries need the exec bit.
                        if (entry.name.startsWith("bin/") || entry.name.contains("/lib/") ||
                            entry.name.endsWith(".so") || entry.name.startsWith("lib/")
                        ) {
                            target.setExecutable(true, true)
                        }

                        entries++
                        if (entries % 40 == 0) {
                            onProgress(Progress(written, entries))
                        }
                        zip.closeEntry()
                    }
                }
            }

            File(root, ".version").writeText(LocalToolchain.VERSION)
            onProgress(Progress(written, entries))

            // A truncated or wrongly-formatted archive would otherwise look
            // like a successful install.
            check(toolchain.isComplete) {
                "The bundled toolchain archive did not produce a usable toolchain " +
                    "($written bytes, $entries entries)."
            }
        }
    }

    fun expectedSizeBytes(): Long = runCatching {
        context.assets.openFd(archiveName).length
    }.getOrDefault(0L)
}
