@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package splice.hub.front

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlinx.serialization.json.JsonObject
import platform.posix.EINTR
import platform.posix.ENOENT
import platform.posix.O_NOFOLLOW
import platform.posix.O_RDONLY
import platform.posix.close
import platform.posix.errno
import platform.posix.open
import platform.posix.read
import splice.hub.io.Bytes

// why: fixed-size chunks bound each syscall buffer without truncating the retained outstanding ledger.
private const val STATE_READ_CHUNK = 64 * 1024

/** Only a genuinely absent first-run snapshot is empty. An unreadable retained ledger must refuse restart. */
internal class HubStateReader {
    fun load(path: String): JsonObject? {
        val fd = open(path, O_RDONLY or O_NOFOLLOW)
        if (fd < 0) {
            check(errno == ENOENT) { "the retained MCP front state could not be opened" }
            return null
        }
        val bytes = try {
            readAll(fd)
        } finally {
            close(fd)
        }
        return checkNotNull(JsonText.objectOrNull(bytes.text(0, bytes.size))) {
            "the retained MCP front state is not a complete JSON object"
        }
    }

    private fun readAll(fd: Int): Bytes {
        val out = Bytes()
        val buffer = ByteArray(STATE_READ_CHUNK)
        while (true) {
            val count = buffer.usePinned { read(fd, it.addressOf(0), buffer.size.convert()) }.toInt()
            when {
                count > 0 -> out.append(buffer, count)
                count == 0 -> return out
                errno != EINTR -> error("the retained MCP front state could not be read completely")
            }
        }
    }
}
