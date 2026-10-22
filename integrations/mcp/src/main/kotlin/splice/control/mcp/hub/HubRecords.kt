// NEW: Oct 13 — holder records live beside the front's state without being front records themselves.
package splice.control.mcp.hub

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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

/** A generation's holder process and unique systemd scope, known before its child can be attested. */
internal data class HubHolderLaunch(
    val generation: String,
    val holderPid: Long,
    val scope: String,
)

/** The holder and child in one generation-specific systemd scope. */
internal data class HubHolderOwner(
    val generation: String,
    val holderPid: Long,
    val childPid: Long,
    val scope: String,
) {
    fun launch(): HubHolderLaunch = HubHolderLaunch(generation, holderPid, scope)
}

/** The holder record's three concerns stay separate: files, exact ownership, and generation exit evidence. */
internal class HubRecords(dir: Path) {
    val files = HubRecordFiles(dir)
    val ownership = HubRecordOwnership(files)
    val exits = HubHolderExitEvidence(dir, files, ownership)
}

/** Reads and atomically replaces the reusable record that names one socket. */
internal class HubRecordFiles(private val dir: Path) {
    private val named = Regex("[0-9a-f]{$KEY_HEX}\\.json")

    fun all(): List<HubHolderRecord> {
        if (!Files.isDirectory(dir)) return emptyList()
        val paths = Files.list(dir).use { it.filter { path -> named.matches(path.fileName.toString()) }.toList() }
        return paths.mapNotNull { path -> read(path)?.takeIf(::namesServer)?.let { HubHolderRecord(path, it) } }
    }

    fun serverBySocket(): Map<String, String> = all()
        .mapNotNull { record ->
            JsonScalars.str(record.body, "server")
                ?.takeIf { live(record) }
                ?.let { socketOf(record.path).toString() to it }
        }
        .toMap()

    fun begin(key: String, body: JsonObject): HubHolderRecord? {
        val path = file(key)
        return write(path, body).takeIf { it }?.let { HubHolderRecord(path, body) }
    }

    fun live(record: HubHolderRecord): Boolean = JsonScalars.str(record.body, HOLDER_STATE) != HOLDER_EXITED

    fun socketOf(record: Path): Path = record.resolveSibling(record.fileName.toString().removeSuffix(".json") + ".sock")

    fun read(record: Path): JsonObject? = try {
        record.takeIf(Files::isRegularFile)?.let { JsonRpcCodec().parse(Files.readString(it)) }
    } catch (_: IOException) {
        null
    }

    fun write(path: Path, body: JsonObject): Boolean = try {
        SecureFile.writeAtomic0600(path, JsonWire.string(body))
        true
    } catch (_: IOException) {
        false
    }

    private fun file(key: String): Path = dir.resolve("${key.take(KEY_HEX)}.json")

    private fun namesServer(body: JsonObject): Boolean = JsonScalars.str(body, "server") != null
}

/** Decodes and updates an owner only while its exact socket record still occupies the file. */
internal class HubRecordOwnership(private val files: HubRecordFiles) {
    fun attest(record: HubHolderRecord, owner: HubHolderOwner): HubHolderRecord? {
        val current = files.read(record.path)?.takeIf { it == record.body } ?: return null
        if (launch(current)?.let { it != owner.launch() } == true) return null
        val attested = JsonObject(
            current + mapOf(
                HOLDER_GENERATION to JsonPrimitive(owner.generation),
                HOLDER_PID to JsonPrimitive(owner.holderPid),
                HOLDER_CHILD_PID to JsonPrimitive(owner.childPid),
                HOLDER_SCOPE to JsonPrimitive(owner.scope),
                HOLDER_STATE to JsonPrimitive(HOLDER_LIVE),
            ),
        )
        return files.write(record.path, attested).takeIf { it }?.let { HubHolderRecord(record.path, attested) }
    }

    fun owner(record: HubHolderRecord): HubHolderOwner? = launch(record.body)?.let { launch ->
        JsonScalars.long(record.body, HOLDER_CHILD_PID)?.let { child ->
            HubHolderOwner(launch.generation, launch.holderPid, child, launch.scope)
        }
    }

    fun launch(record: HubHolderRecord): HubHolderLaunch? = launch(record.body)

    fun currentExit(record: HubHolderRecord, owner: HubHolderOwner): Boolean =
        files.read(record.path)?.let { current ->
            JsonScalars.str(current, HOLDER_STATE) == HOLDER_EXITED && owner(current) == owner
        } == true

    fun currentLaunchExit(record: HubHolderRecord): Boolean =
        files.read(record.path)?.let { current ->
            JsonScalars.str(current, HOLDER_STATE) == HOLDER_EXITED &&
                JsonScalars.long(current, HOLDER_EXIT_CONFIRMED_AT) != null &&
                launch(current) == launch(record.body)
        } == true

    private fun owner(body: JsonObject): HubHolderOwner? = launch(body)?.let { launch ->
        JsonScalars.long(body, HOLDER_CHILD_PID)?.let { child ->
            HubHolderOwner(launch.generation, launch.holderPid, child, launch.scope)
        }
    }

    private fun launch(body: JsonObject): HubHolderLaunch? {
        val generation = JsonScalars.str(body, HOLDER_GENERATION)
        val holder = JsonScalars.long(body, HOLDER_PID)
        val scope = JsonScalars.str(body, HOLDER_SCOPE)
        if (listOf(generation, holder, scope).any { it == null }) return null
        return HubHolderLaunch(requireNotNull(generation), requireNotNull(holder), requireNotNull(scope))
    }
}

/** Exit evidence is generation-addressed, unlike the holder's reusable socket record. */
internal class HubHolderExitEvidence(
    dir: Path,
    private val files: HubRecordFiles,
    private val ownership: HubRecordOwnership,
) {
    private val exits = dir.resolve(HOLDER_EXITS_DIR)

    fun confirm(record: HubHolderRecord, owner: HubHolderOwner): Boolean =
        confirm(record, owner.launch(), owner.childPid)

    fun confirmLaunch(record: HubHolderRecord): Boolean =
        ownership.launch(record)?.let { confirm(record, it, null) } ?: false

    fun confirmed(record: HubHolderRecord): Boolean {
        if (JsonScalars.str(record.body, HOLDER_STATE) == HOLDER_EXITED) {
            return JsonScalars.long(record.body, HOLDER_EXIT_CONFIRMED_AT) != null
        }
        val generation = JsonScalars.str(record.body, HOLDER_GENERATION) ?: return false
        return confirmed(files.socketOf(record.path).toString(), generation)
    }

    fun confirmed(socket: String, generation: String): Boolean {
        val receipt = read(file(socket, generation)) ?: return false
        if (JsonScalars.str(receipt, HOLDER_EXIT_SOCKET) != socket) return false
        return receiptLaunch(receipt)?.generation == generation &&
            JsonScalars.long(receipt, HOLDER_EXIT_CONFIRMED_AT) != null
    }

    private fun confirm(record: HubHolderRecord, launch: HubHolderLaunch, childPid: Long?): Boolean {
        val socket = files.socketOf(record.path).toString()
        if (confirmed(socket, launch.generation)) return true
        val confirmedAt = System.currentTimeMillis()
        return persist(socket, launch, childPid, confirmedAt) &&
            (markExited(record, confirmedAt) || confirmed(socket, launch.generation))
    }

    private fun markExited(record: HubHolderRecord, confirmedAt: Long): Boolean =
        files.read(record.path)?.takeIf { current -> sameGeneration(record.body, current) }?.let { current ->
            files.write(
                record.path,
                JsonObject(
                    current + mapOf(
                        HOLDER_STATE to JsonPrimitive(HOLDER_EXITED),
                        HOLDER_EXIT_CONFIRMED_AT to JsonPrimitive(confirmedAt),
                    ),
                ),
            )
        } == true

    private fun sameGeneration(record: JsonObject, current: JsonObject): Boolean =
        JsonScalars.str(record, HOLDER_GENERATION) == JsonScalars.str(current, HOLDER_GENERATION)

    private fun persist(socket: String, launch: HubHolderLaunch, childPid: Long?, confirmedAt: Long): Boolean = try {
        Files.createDirectories(exits)
        write(file(socket, launch.generation), receipt(socket, launch, childPid, confirmedAt))
    } catch (_: IOException) {
        false
    }

    private fun receipt(
        socket: String,
        launch: HubHolderLaunch,
        childPid: Long?,
        confirmedAt: Long,
    ): JsonObject = buildJsonObject {
        put(HOLDER_EXIT_SOCKET, socket)
        put(HOLDER_GENERATION, launch.generation)
        put(HOLDER_PID, launch.holderPid)
        put(HOLDER_SCOPE, launch.scope)
        childPid?.let { put(HOLDER_CHILD_PID, it) }
        put(HOLDER_EXIT_CONFIRMED_AT, confirmedAt)
    }

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

    private fun receiptLaunch(body: JsonObject): HubHolderLaunch? {
        val generation = JsonScalars.str(body, HOLDER_GENERATION)
        val holder = JsonScalars.long(body, HOLDER_PID)
        val scope = JsonScalars.str(body, HOLDER_SCOPE)
        if (listOf(generation, holder, scope).any { it == null }) return null
        return HubHolderLaunch(requireNotNull(generation), requireNotNull(holder), requireNotNull(scope))
    }

    private fun write(path: Path, body: JsonObject): Boolean = try {
        SecureFile.writeAtomic0600(path, JsonWire.string(body))
        true
    } catch (_: IOException) {
        false
    }
}
