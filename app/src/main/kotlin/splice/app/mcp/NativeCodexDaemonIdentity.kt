package splice.app.mcp

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import splice.core.util.JsonScalars
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

// why: Codex's daemon.pid is process metadata, not an unbounded input file.
private const val CODEX_DAEMON_METADATA_LIMIT_BYTES = 65_536L

// why: /proc/<pid>/stat field 22 is field 19 after its parenthesized command name.
private const val PROC_START_TICKS_INDEX_AFTER_COMMAND = 19

internal data class NativeCodexDaemonIdentity(
    val pid: Long,
    val bootId: String,
    val startTicks: Long,
)

/** Reads and revalidates Codex's per-home daemon generation before its socket is trusted. */
internal class NativeCodexDaemonIdentityReader {
    private val procRoot = Path.of("/proc")

    fun read(home: Path): NativeCodexDaemonIdentity? = try {
        readMetadata(home)
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    } catch (_: SerializationException) {
        null
    }

    fun isCurrent(identity: NativeCodexDaemonIdentity): Boolean = try {
        val bootId = Files.readString(procRoot.resolve("sys/kernel/random/boot_id")).trim()
        bootId == identity.bootId && processStartTicks(identity.pid) == identity.startTicks
    } catch (_: IOException) {
        false
    } catch (_: SecurityException) {
        false
    }

    private fun readMetadata(home: Path): NativeCodexDaemonIdentity? {
        val metadata = home.toAbsolutePath().normalize()
            .resolve("app-server-daemon")
            .resolve("daemon.pid")
        return if (usableMetadata(metadata)) decode(Files.readString(metadata)) else null
    }

    private fun usableMetadata(metadata: Path): Boolean =
        Files.isRegularFile(metadata, LinkOption.NOFOLLOW_LINKS) &&
            Files.size(metadata) <= CODEX_DAEMON_METADATA_LIMIT_BYTES

    private fun decode(text: String): NativeCodexDaemonIdentity? =
        (Json.parseToJsonElement(text) as? JsonObject)?.let(::identity)

    private fun identity(root: JsonObject): NativeCodexDaemonIdentity? {
        val pid = JsonScalars.long(root, "pid")?.takeIf { it > 0L }
        val process = root["processIdentity"] as? JsonObject
        val bootId = process?.let { JsonScalars.str(it, "bootId") }?.takeIf(String::isNotBlank)
        val startTicks = process?.let { JsonScalars.long(it, "startTicks") }?.takeIf { it > 0L }
        return completeIdentity(pid, bootId, startTicks)
    }

    private fun completeIdentity(
        pid: Long?,
        bootId: String?,
        startTicks: Long?,
    ): NativeCodexDaemonIdentity? = when {
        pid == null -> null
        bootId == null -> null
        startTicks == null -> null
        else -> NativeCodexDaemonIdentity(pid, bootId, startTicks)
    }

    private fun processStartTicks(pid: Long): Long? {
        val stat = Files.readString(procRoot.resolve(pid.toString()).resolve("stat"))
        val commandEnd = stat.lastIndexOf(')')
        return stat.takeIf { commandEnd >= 0 }
            ?.substring(commandEnd + 1)
            ?.trim()
            ?.split(Regex("\\s+"))
            ?.getOrNull(PROC_START_TICKS_INDEX_AFTER_COMMAND)
            ?.toLongOrNull()
    }
}
