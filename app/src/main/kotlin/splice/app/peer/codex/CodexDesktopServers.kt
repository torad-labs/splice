package splice.app.peer.codex

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import splice.app.mcp.NativeCodexSocketPath
import splice.sessions.peer.codex.CodexBinding
import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.SocketChannel
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

// why: desktop IPC is a same-user control surface; group/other access is not allowed by its own protocol.
private const val OTHER_ACCESS_BITS = 0x3F

// why: a full desktop socket backlog must not leave attachment outside the local discovery deadline.
private const val DESKTOP_CONNECT_TIMEOUT_MS = 5_000L

/** Finds the desktop's actual owner through its IPC router, without loading a thread in another app-server. */
internal class CodexDesktopServers(private val io: CoroutineDispatcher) {
    suspend fun open(binding: CodexBinding): CodexOpen.Open? {
        val path = Path.of(binding.home).resolve("ipc/ipc.sock")
        if (!secure(path)) return null
        val probe = connect(path, binding.thread) ?: return null
        val reachable = coroutineScope {
            val reader = async { probe.read() }
            try {
                probe.discover() == null
            } finally {
                probe.close()
                reader.await()
            }
        }
        return if (reachable) connect(path, binding.thread)?.let { CodexOpen.Open(it) } else null
    }

    private suspend fun connect(path: Path, thread: String): CodexDesktopThread? {
        var channel: SocketChannel? = null
        var connected: CodexDesktopThread? = null
        try {
            connected = withTimeoutOrNull(DESKTOP_CONNECT_TIMEOUT_MS) {
                runInterruptible(io) {
                    val opened = SocketChannel.open(StandardProtocolFamily.UNIX)
                    channel = opened
                    opened.connect(UnixDomainSocketAddress.of(path))
                    CodexDesktopThread(CodexIpcRpc(CodexIpcSocket(opened), io), thread)
                }
            }
            return connected
        } catch (_: IOException) {
            return null
        } finally {
            if (connected == null) channel?.close()
        }
    }

    private fun secure(path: Path): Boolean {
        return try {
            val userName = System.getProperty("user.name") ?: return false
            val owner = FileSystems.getDefault().userPrincipalLookupService.lookupPrincipalByName(userName)
            NativeCodexSocketPath().present(path) && listOf(path, path.parent).all { file ->
                val mode = Files.getAttribute(file, "unix:mode", LinkOption.NOFOLLOW_LINKS) as? Int
                Files.getOwner(file, LinkOption.NOFOLLOW_LINKS) == owner && mode != null &&
                    mode and OTHER_ACCESS_BITS == 0
            }
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        } catch (_: UnsupportedOperationException) {
            false
        }
    }
}
