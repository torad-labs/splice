// NEW: server mode (docs/specs/server-mode.md, F5, ledger-export). An export to a folder on this machine
// (`to = "file:///var/lib/splice-usage"`): each hour's file is replaced atomically and owner-only, so a reader never
// sees half an hour, and the folders under it are made as they are needed.
package splice.server.export

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import splice.core.util.SafeFailureText
import splice.core.util.SecureFile
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

internal class FolderTarget(private val dir: Path, private val io: CoroutineDispatcher) : ExportTarget {
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
}
