package splice.app.mcp

import java.io.IOException
import java.nio.file.DirectoryIteratorException
import java.nio.file.InvalidPathException
import java.nio.file.Path

/** A path the bridge may reach, plus the recorded Linux daemon generation that must answer that connection. */
internal data class NativeCodexSocketEndpoint(
    val path: Path,
    val binding: NativeCodexSocketBinding?,
)

/** The daemon generation and its listener's procfs socket identity, never a filesystem inode. */
internal data class NativeCodexSocketBinding(
    val identity: NativeCodexDaemonIdentity,
    val listenerKernelInode: Long,
)

internal data class NativeCodexListener(val path: Path, val kernelInode: Long)

/** Finds one listener held by the daemon generation registered in this CODEX_HOME. */
internal class NativeCodexDaemonSocketDiscovery {
    private val identityReader = NativeCodexDaemonIdentityReader()
    private val listeners = NativeCodexDaemonListeners()
    private val socketPath = NativeCodexSocketPath()

    fun find(home: Path): NativeCodexSocketEndpoint? = try {
        findOwnedSocket(home)
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    } catch (_: InvalidPathException) {
        null
    } catch (_: DirectoryIteratorException) {
        null
    } catch (_: UnsupportedOperationException) {
        null
    }

    /** Older non-Linux Codex builds provide only a socket path, which is separately vetted before use. */
    fun legacy(path: Path): NativeCodexSocketEndpoint? =
        path.takeIf { !linux() && socketPath.present(it) }?.let { NativeCodexSocketEndpoint(it, null) }

    private fun findOwnedSocket(home: Path): NativeCodexSocketEndpoint? {
        val identity = identityReader.read(home) ?: return null
        val listener = listeners.forDaemon(identity.pid).singleOrNull() ?: return null
        return if (socketPath.present(listener.path) && identityReader.isCurrent(identity)) {
            NativeCodexSocketEndpoint(
                listener.path,
                NativeCodexSocketBinding(identity, listener.kernelInode),
            )
        } else {
            null
        }
    }

    private fun linux(): Boolean = System.getProperty("os.name").equals("linux", ignoreCase = true)
}
