package dev.rustbook.pocket.runner

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Unpacks the bundled Rust toolchain from assets into [LocalToolchain.root].
 *
 * The archive is stored uncompressed inside the APK (`noCompress += "zip"`), so
 * unpacking is mostly a stream copy. It happens once per toolchain version:
 * `build-toolchain.sh` writes `toolchain/version.txt` next to the archive, and
 * the app compares that with the marker it left after the previous unpack.
 *
 * There is deliberately no version constant in Kotlin. When there was one, a
 * rebuilt archive was skipped because the constant had not been bumped.
 */
class LocalToolchainInstaller(
    private val context: Context,
    private val toolchain: LocalToolchain,
) {

    private val archiveName = "toolchain/toolchain.zip"
    private val versionAsset = "toolchain/version.txt"

    data class Progress(val bytesWritten: Long, val entries: Int)

    /** Version recorded next to the archive, or `null` when there is no archive. */
    fun packagedVersion(): String? = runCatching {
        context.assets.open(versionAsset).bufferedReader().use { it.readText().trim() }
    }.getOrNull()?.ifBlank { null }

    fun isPackaged(): Boolean = packagedVersion() != null

    suspend fun ensureInstalled(
        onProgress: (Progress) -> Unit = {},
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val version = packagedVersion()
            ?: return@withContext Result.failure(IllegalStateException("No toolchain archive"))

        if (!toolchain.needsInstall(version)) {
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
                            written += zip.copyTo(output)
                        }

                        // The compiler, the linker and the shared libraries need
                        // the exec bit; the sysroot data just needs to be readable.
                        if (entry.name.startsWith("bin/") ||
                            entry.name.endsWith(".so") ||
                            entry.name.contains(".so.")
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

            toolchain.markInstalled(version)
            onProgress(Progress(written, entries))
        }
    }

    fun expectedSizeBytes(): Long = runCatching {
        context.assets.openFd(archiveName).length
    }.getOrDefault(0L)
}
