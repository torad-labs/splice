package splice.app.mcp

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

// why: unix:mode stores the file type in its upper four bits.
private const val UNIX_MODE_TYPE_MASK = 0xF000

// why: S_IFSOCK is the 0xC000 file-type value encoded in unix:mode.
private const val UNIX_MODE_SOCKET_TYPE = 0xC000

/** Vets the filesystem object named by a discovered Unix socket path. */
internal class NativeCodexSocketPath {
    fun present(path: Path): Boolean = try {
        val mode = Files.getAttribute(path, "unix:mode", LinkOption.NOFOLLOW_LINKS) as? Int
        mode != null && mode and UNIX_MODE_TYPE_MASK == UNIX_MODE_SOCKET_TYPE
    } catch (_: java.io.IOException) {
        false
    } catch (_: SecurityException) {
        false
    } catch (_: UnsupportedOperationException) {
        false
    }
}
