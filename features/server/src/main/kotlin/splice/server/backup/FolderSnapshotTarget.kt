// NEW: server mode (docs/specs/server-mode.md, F14). Folder-backed durable snapshot target (`file:///dir`): writes
// owner-only atomically through SecureFile and reads back distinguishing missing from unreadable.
package splice.server.backup

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import splice.core.util.SafeFailureText
import splice.core.util.SecureFile
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

internal class FolderSnapshotTarget(
    private val dir: Path,
    private val io: CoroutineDispatcher,
) : SnapshotTarget {
    override suspend fun put(key: String, bytes: ByteArray): String? = withContext(io) {
        val file = dir.resolve(key)
        try {
            Files.createDirectories(file.parent)
            SecureFile.writeAtomic0600(file, String(bytes, Charsets.UTF_8))
            null
        } catch (e: IOException) {
            "could not write $file: ${SafeFailureText.render(e)}"
        }
    }

    override fun get(key: String): SnapshotRead {
        val file = dir.resolve(key)
        return when {
            !Files.exists(dir) -> SnapshotRead.Failed("backup directory does not exist: $dir")
            !Files.isDirectory(dir) -> SnapshotRead.Failed("backup path is not a directory: $dir")
            !Files.exists(file) -> SnapshotRead.Missing
            else -> readBytes(file)
        }
    }

    private fun readBytes(file: Path): SnapshotRead = try {
        SnapshotRead.Found(Files.readAllBytes(file))
    } catch (e: IOException) {
        SnapshotRead.Failed("could not read $file: ${SafeFailureText.render(e)}")
    }
}
