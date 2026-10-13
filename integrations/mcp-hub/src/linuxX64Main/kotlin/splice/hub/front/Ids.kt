// The two identities the front makes up: a session's id, and which holder process listens at a socket path now.
@file:OptIn(ExperimentalForeignApi::class)

package splice.hub.front

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import platform.posix.O_RDONLY
import platform.posix.close
import platform.posix.open
import platform.posix.read
import platform.posix.stat

object Ids {
    /** A new session id: 16 random bytes in hex. */
    fun session(): String {
        val bytes = ByteArray(ID_BYTES)
        val fd = open("/dev/urandom", O_RDONLY)
        bytes.usePinned { read(fd, it.addressOf(0), ID_BYTES.convert()) }
        close(fd)
        return bytes.joinToString("") { (it.toInt() and BYTE_MASK).toString(HEX).padStart(2, '0') }
    }

    /** Which holder listens at [path] now: a new holder binds a new socket file, so its inode or ctime differs. */
    fun socket(path: String): String? = memScoped {
        val st = alloc<stat>()
        if (stat(path, st.ptr) != 0) null else "${st.st_ino}-${st.st_ctim.tv_sec}-${st.st_ctim.tv_nsec}"
    }
}

private const val ID_BYTES = 16
private const val BYTE_MASK = 0xff
private const val HEX = 16
