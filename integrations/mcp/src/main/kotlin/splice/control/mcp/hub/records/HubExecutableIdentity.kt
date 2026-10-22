package splice.control.mcp.hub.records

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/** An executable inode remains stable when its installed pathname is atomically replaced or marked deleted. */
internal class HubExecutableIdentity {
    fun of(path: Path): String? = try {
        Files.readAttributes(path, "unix:dev,ino").let { attributes ->
            val device = attributes["dev"]
            val inode = attributes["ino"]
            if (device == null || inode == null) null else "$device:$inode"
        }
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    } catch (_: UnsupportedOperationException) {
        null
    }
}
