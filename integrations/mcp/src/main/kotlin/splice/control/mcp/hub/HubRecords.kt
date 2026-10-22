// NEW: Oct 13 — holder records live beside the front's state without being front records themselves.
package splice.control.mcp.hub

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.control.mcp.JsonRpcCodec
import splice.core.util.JsonScalars
import splice.core.util.JsonWire
import splice.core.util.SecureFile
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat

// why: a holder's socket and record are named by its key's first KEY_HEX hex digits, short enough for a Unix socket path
internal const val KEY_HEX = 16
internal const val HOLDER_STATE = "state"
internal const val HOLDER_LIVE = "live"
internal const val HOLDER_EXITED = "exited"
internal const val HOLDER_GENERATION = "generation"
private const val HOLDER_PID = "pid"
private const val HOLDER_CHILD_PID = "child_pid"
private const val HOLDER_SCOPE = "scope"
private const val HOLDER_EXIT_CONFIRMED_AT = "exit_confirmed_at"
private const val HOLDER_EXIT_SOCKET = "socket"
private const val HOLDER_EXITS_DIR = "holder-exits"

/** One record at its fixed socket path. The body remains the source of an exact process-generation claim. */
internal data class HubHolderRecord(val path: Path, val body: JsonObject)

/** The holder and child in one generation-specific systemd scope. */
internal data class HubHolderOwner(
    val generation: String,
    val holderPid: Long,
    val childPid: Long,
    val scope: String,
)

internal class HubRecords(private val dir: Path) {
    private val named = Regex("[0-9a-f]{$KEY_HEX}\\.json")
    private val exits = HubHolderExitEvidence(dir)

    /** Every holder record, each with what it says. */
    fun all(): List<HubHolderRecord> {
        if (!Files.isDirectory(dir)) return emptyList()
        val files = Files.list(dir).use { paths ->
            paths.filter { named.matches(it.fileName.toString()) }.toList()
        }
        return files.mapNotNull { path ->
            read(path)
                ?.takeIf { JsonScalars.str(it, "server") != null }
                ?.let { HubHolderRecord(path, it) }
        }
    }

    /** Each live recorded holder's socket, by the server its record names. */
    fun serverBySocket(): Map<String, String> = all()
        .mapNotNull { record ->
            JsonScalars.str(record.body, "server")
                ?.takeIf { JsonScalars.str(record.body, HOLDER_STATE) != HOLDER_EXITED }
                ?.let { socketOf(record.path).toString() to it }
        }
        .toMap()

    /** Writes a newly started holder before it is ready, so a daemon crash cannot orphan its scope. */
    fun begin(key: String, body: JsonObject): HubHolderRecord? {
        val path = file(key)
        return write(path, body).takeIf { it }?.let { HubHolderRecord(path, body) }
    }

    /** Adds the exact live owner only while the same record still occupies this socket. */
    fun attest(record: HubHolderRecord, owner: HubHolderOwner): HubHolderRecord? {
        val current = read(record.path) ?: return null
        if (current != record.body) return null
        val attested = JsonObject(
            current + mapOf(
                HOLDER_GENERATION to JsonPrimitive(owner.generation),
                HOLDER_PID to JsonPrimitive(owner.holderPid),
                HOLDER_CHILD_PID to JsonPrimitive(owner.childPid),
                HOLDER_SCOPE to JsonPrimitive(owner.scope),
                HOLDER_STATE to JsonPrimitive(HOLDER_LIVE),
            ),
        )
        return write(record.path, attested).takeIf { it }?.let { HubHolderRecord(record.path, attested) }
    }

    /** Persists this generation's exact exited scope before its reusable socket record can be replaced. */
    fun confirmExit(record: HubHolderRecord, owner: HubHolderOwner): Boolean {
        val socket = socketOf(record.path).toString()
        return if (exits.confirmed(socket, owner.generation)) {
            true
        } else {
            read(record.path)
                ?.takeIf { current -> current == record.body && owner(current) == owner }
                ?.let { current ->
                    val confirmedAt = System.currentTimeMillis()
                    exits.persist(socket, owner, confirmedAt) && (
                        write(
                            record.path,
                            JsonObject(
                                current + mapOf(
                                    HOLDER_STATE to JsonPrimitive(HOLDER_EXITED),
                                    HOLDER_EXIT_CONFIRMED_AT to JsonPrimitive(confirmedAt),
                                ),
                            ),
                        ) || exits.confirmed(socket, owner.generation)
                        )
                } ?: false
        }
    }

    fun isLive(record: HubHolderRecord): Boolean =
        JsonScalars.str(record.body, HOLDER_STATE) != HOLDER_EXITED

    fun isConfirmedExit(record: HubHolderRecord): Boolean {
        val exited = JsonScalars.str(record.body, HOLDER_STATE) == HOLDER_EXITED &&
            JsonScalars.long(record.body, HOLDER_EXIT_CONFIRMED_AT) != null
        val generation = JsonScalars.str(record.body, HOLDER_GENERATION)
        return exited || generation?.let { isGenerationExitConfirmed(socketOf(record.path).toString(), it) } == true
    }

    /** Reads durable exit evidence, so a newer owner at this socket cannot erase an old generation's exit. */
    fun isGenerationExitConfirmed(socket: String, generation: String): Boolean = exits.confirmed(socket, generation)

    fun owner(record: HubHolderRecord): HubHolderOwner? = owner(record.body)

    fun socketOf(record: Path): Path =
        record.resolveSibling(record.fileName.toString().removeSuffix(".json") + ".sock")

    fun file(key: String): Path = dir.resolve("${key.take(KEY_HEX)}.json")

    fun read(record: Path): JsonObject? = try {
        record.takeIf(Files::isRegularFile)?.let { JsonRpcCodec().parse(Files.readString(it)) }
    } catch (_: IOException) {
        null
    }

    private fun owner(body: JsonObject): HubHolderOwner? {
        val generation = JsonScalars.str(body, HOLDER_GENERATION)
        val holderPid = JsonScalars.long(body, HOLDER_PID)
        val childPid = JsonScalars.long(body, HOLDER_CHILD_PID)
        val scope = JsonScalars.str(body, HOLDER_SCOPE)
        return when {
            generation == null -> null
            holderPid == null -> null
            childPid == null -> null
            scope == null -> null
            else -> HubHolderOwner(generation, holderPid, childPid, scope)
        }
    }

    private fun write(path: Path, body: JsonObject): Boolean = try {
        SecureFile.writeAtomic0600(path, JsonWire.string(body))
        true
    } catch (_: IOException) {
        false
    }
}

/** Exit evidence is generation-addressed, unlike the holder's reusable socket record. */
private class HubHolderExitEvidence(dir: Path) {
    private val exits = dir.resolve(HOLDER_EXITS_DIR)

    fun persist(socket: String, owner: HubHolderOwner, confirmedAt: Long): Boolean = try {
        Files.createDirectories(exits)
        write(
            file(socket, owner.generation),
            JsonObject(
                mapOf(
                    HOLDER_EXIT_SOCKET to JsonPrimitive(socket),
                    HOLDER_GENERATION to JsonPrimitive(owner.generation),
                    HOLDER_PID to JsonPrimitive(owner.holderPid),
                    HOLDER_CHILD_PID to JsonPrimitive(owner.childPid),
                    HOLDER_SCOPE to JsonPrimitive(owner.scope),
                    HOLDER_EXIT_CONFIRMED_AT to JsonPrimitive(confirmedAt),
                ),
            ),
        )
    } catch (_: IOException) {
        false
    }

    fun confirmed(socket: String, generation: String): Boolean =
        read(file(socket, generation))?.let { evidence ->
            JsonScalars.str(evidence, HOLDER_EXIT_SOCKET) == socket &&
                owner(evidence)?.generation == generation &&
                JsonScalars.long(evidence, HOLDER_EXIT_CONFIRMED_AT) != null
        } == true

    private fun file(socket: String, generation: String): Path = exits.resolve(
        HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest("$socket\n$generation".toByteArray(StandardCharsets.UTF_8)),
        ) + ".json",
    )

    private fun read(path: Path): JsonObject? = try {
        path.takeIf(Files::isRegularFile)?.let { JsonRpcCodec().parse(Files.readString(it)) }
    } catch (_: IOException) {
        null
    }

    private fun owner(body: JsonObject): HubHolderOwner? {
        val generation = JsonScalars.str(body, HOLDER_GENERATION)
        val holderPid = JsonScalars.long(body, HOLDER_PID)
        val childPid = JsonScalars.long(body, HOLDER_CHILD_PID)
        val scope = JsonScalars.str(body, HOLDER_SCOPE)
        return when {
            generation == null -> null
            holderPid == null -> null
            childPid == null -> null
            scope == null -> null
            else -> HubHolderOwner(generation, holderPid, childPid, scope)
        }
    }

    private fun write(path: Path, body: JsonObject): Boolean = try {
        SecureFile.writeAtomic0600(path, JsonWire.string(body))
        true
    } catch (_: IOException) {
        false
    }
}
