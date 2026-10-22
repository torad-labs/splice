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
internal const val HOLDER_STARTING = "starting"
internal const val HOLDER_LIVE = "live"
internal const val HOLDER_EXITED = "exited"

private const val HOLDER_EXIT_CONFIRMED_AT = "exit_confirmed_at"
private const val HOLDER_EXITS_DIR = "holder-exits"
private const val HOLDER_LAUNCHER_STARTED_AT = "launcher_started_at"

/** One record at its fixed socket path. The body remains the source of an exact process-generation claim. */
internal data class HubHolderRecord(val path: Path, val body: JsonObject)

private sealed class HubRecordScan {
    data class Readable(val records: List<HubHolderRecord>) : HubRecordScan()

    object Unreadable : HubRecordScan()
}

/** A generation-specific systemd scope recorded before a holder process can escape. */
internal data class HubHolderScopeClaim(
    val generation: String,
    val scope: String,
)

/** A generation's holder process and unique systemd scope, known before its child can be attested. */
internal data class HubHolderLaunch(
    val generation: String,
    val holderPid: Long,
    val scope: String,
    val holderStartedAt: Long? = null,
) {
    fun claim(): HubHolderScopeClaim = HubHolderScopeClaim(generation, scope)
}

/** The holder and child in one generation-specific systemd scope. */
internal data class HubHolderOwner(
    val generation: String,
    val holderPid: Long,
    val childPid: Long,
    val scope: String,
    val holderStartedAt: Long? = null,
) {
    fun launch(): HubHolderLaunch = HubHolderLaunch(generation, holderPid, scope, holderStartedAt)
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

    fun all(): List<HubHolderRecord> = (scan() as? HubRecordScan.Readable)?.records.orEmpty()

    /** A malformed or unreadable record blocks lifecycle changes until an operator repairs its durable state. */
    fun readable(): Boolean = scan() is HubRecordScan.Readable

    fun serverBySocket(): Map<String, String> = all()
        .mapNotNull { record ->
            JsonScalars.str(record.body, HubFields.SERVER)
                ?.takeIf { live(record) }
                ?.let { socketOf(record.path).toString() to it }
        }
        .toMap()

    fun begin(key: String, body: JsonObject): HubHolderRecord? {
        if (!readable()) return null
        val path = file(key)
        return write(path, body).takeIf { it }?.let { HubHolderRecord(path, body) }
    }

    fun live(record: HubHolderRecord): Boolean = JsonScalars.str(record.body, HubFields.STATE) != HOLDER_EXITED

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

    private fun scan(): HubRecordScan = try {
        when {
            Files.notExists(dir) -> HubRecordScan.Readable(emptyList())
            !Files.isDirectory(dir) -> HubRecordScan.Unreadable
            else -> listed()
        }
    } catch (_: IOException) {
        HubRecordScan.Unreadable
    }

    private fun listed(): HubRecordScan {
        val records = Files.list(dir).use { paths ->
            paths.filter { path -> named.matches(path.fileName.toString()) }.toList().map { path ->
                read(path)?.takeIf(::namesServer)?.let { body -> HubHolderRecord(path, body) }
            }
        }
        return records.takeIf { it.none { record -> record == null } }
            ?.map { requireNotNull(it) }
            ?.let(HubRecordScan::Readable)
            ?: HubRecordScan.Unreadable
    }

    private fun file(key: String): Path = dir.resolve("${key.take(KEY_HEX)}.json")

    private fun namesServer(body: JsonObject): Boolean = JsonScalars.str(body, HubFields.SERVER) != null
}

/** Decodes and updates an owner only while its exact socket record still occupies the file. */
internal class HubRecordOwnership(private val files: HubRecordFiles) {
    fun attest(record: HubHolderRecord, owner: HubHolderOwner): HubHolderRecord? {
        val current = files.read(record.path)?.takeIf { it == record.body } ?: return null
        if (launch(current)?.let { it.claim() != owner.launch().claim() || it.holderPid != owner.holderPid } == true) {
            return null
        }
        val fields = current.toMutableMap().also { values ->
            values[HubFields.GENERATION] = JsonPrimitive(owner.generation)
            values[HubFields.PID] = JsonPrimitive(owner.holderPid)
            values[HubFields.CHILD_PID] = JsonPrimitive(owner.childPid)
            values[HubFields.SCOPE] = JsonPrimitive(owner.scope)
            values[HubFields.STATE] = JsonPrimitive(HOLDER_LIVE)
            owner.holderStartedAt?.let { values[HOLDER_LAUNCHER_STARTED_AT] = JsonPrimitive(it) }
        }
        val attested = JsonObject(fields)
        return files.write(record.path, attested).takeIf { it }?.let { HubHolderRecord(record.path, attested) }
    }

    fun owner(record: HubHolderRecord): HubHolderOwner? = owner(record.body)

    fun launch(record: HubHolderRecord): HubHolderLaunch? = launch(record.body)

    fun claim(record: HubHolderRecord): HubHolderScopeClaim? = claim(record.body)

    fun started(record: HubHolderRecord, launch: HubHolderLaunch): HubHolderRecord? {
        val current = files.read(record.path)?.takeIf { it == record.body } ?: return null
        if (claim(current) != launch.claim() || launch.holderStartedAt == null) return null
        val started = JsonObject(
            current + mapOf(
                HubFields.PID to JsonPrimitive(launch.holderPid),
                HOLDER_LAUNCHER_STARTED_AT to JsonPrimitive(launch.holderStartedAt),
            ),
        )
        return files.write(record.path, started).takeIf { it }?.let { HubHolderRecord(record.path, started) }
    }

    fun currentExit(record: HubHolderRecord, owner: HubHolderOwner): Boolean =
        files.read(record.path)?.let { current ->
            JsonScalars.str(current, HubFields.STATE) == HOLDER_EXITED && owner(current) == owner
        } == true

    fun currentScopeExit(record: HubHolderRecord): Boolean =
        files.read(record.path)?.let { current ->
            JsonScalars.str(current, HubFields.STATE) == HOLDER_EXITED &&
                JsonScalars.long(current, HOLDER_EXIT_CONFIRMED_AT) != null &&
                claim(current) == claim(record.body)
        } == true

    fun currentLaunchExit(record: HubHolderRecord): Boolean =
        files.read(record.path)?.let { current ->
            JsonScalars.str(current, HubFields.STATE) == HOLDER_EXITED &&
                JsonScalars.long(current, HOLDER_EXIT_CONFIRMED_AT) != null &&
                launch(current) == launch(record.body)
        } == true

    private fun owner(body: JsonObject): HubHolderOwner? = launch(body)?.let { launch ->
        JsonScalars.long(body, HubFields.CHILD_PID)?.let { child ->
            HubHolderOwner(launch.generation, launch.holderPid, child, launch.scope, launch.holderStartedAt)
        }
    }

    private fun launch(body: JsonObject): HubHolderLaunch? = claim(body)?.let { claim ->
        JsonScalars.long(body, HubFields.PID)?.let { holder ->
            HubHolderLaunch(
                claim.generation,
                holder,
                claim.scope,
                JsonScalars.long(body, HOLDER_LAUNCHER_STARTED_AT),
            )
        }
    }

    private fun claim(body: JsonObject): HubHolderScopeClaim? {
        val generation = JsonScalars.str(body, HubFields.GENERATION)
        val scope = JsonScalars.str(body, HubFields.SCOPE)
        if (generation == null || scope == null) return null
        return HubHolderScopeClaim(generation, scope)
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
        confirm(record, owner.launch().claim(), owner.childPid)

    fun confirmScope(record: HubHolderRecord): Boolean =
        ownership.claim(record)?.let { confirm(record, it, null) } ?: false

    fun confirmed(record: HubHolderRecord): Boolean =
        if (JsonScalars.str(record.body, HubFields.STATE) == HOLDER_EXITED) {
            JsonScalars.long(record.body, HOLDER_EXIT_CONFIRMED_AT) != null
        } else {
            JsonScalars.str(record.body, HubFields.GENERATION)?.let { generation ->
                confirmed(files.socketOf(record.path).toString(), generation)
            } ?: false
        }

    fun confirmed(socket: String, generation: String, tuple: String? = null): Boolean =
        read(file(socket, generation))?.let { receipt -> matches(receipt, socket, generation, tuple) } == true

    private fun confirm(record: HubHolderRecord, claim: HubHolderScopeClaim, childPid: Long?): Boolean {
        val socket = files.socketOf(record.path).toString()
        val tuple = JsonScalars.str(record.body, HubFields.TUPLE)
        if (confirmed(socket, claim.generation, tuple)) return true
        val confirmedAt = System.currentTimeMillis()
        return persist(socket, claim, childPid, tuple, confirmedAt) &&
            (markExited(record, confirmedAt) || confirmed(socket, claim.generation, tuple))
    }

    private fun markExited(record: HubHolderRecord, confirmedAt: Long): Boolean =
        files.read(record.path)?.takeIf { current -> sameGeneration(record.body, current) }?.let { current ->
            files.write(
                record.path,
                JsonObject(
                    current + mapOf(
                        HubFields.STATE to JsonPrimitive(HOLDER_EXITED),
                        HOLDER_EXIT_CONFIRMED_AT to JsonPrimitive(confirmedAt),
                    ),
                ),
            )
        } == true

    private fun sameGeneration(record: JsonObject, current: JsonObject): Boolean =
        JsonScalars.str(record, HubFields.GENERATION) == JsonScalars.str(current, HubFields.GENERATION)

    private fun persist(
        socket: String,
        claim: HubHolderScopeClaim,
        childPid: Long?,
        tuple: String?,
        confirmedAt: Long,
    ): Boolean = try {
        Files.createDirectories(exits)
        write(file(socket, claim.generation), receipt(socket, claim, childPid, tuple, confirmedAt))
    } catch (_: IOException) {
        false
    }

    private fun receipt(
        socket: String,
        claim: HubHolderScopeClaim,
        childPid: Long?,
        tuple: String?,
        confirmedAt: Long,
    ): JsonObject = buildJsonObject {
        put(HubFields.SOCKET, socket)
        put(HubFields.GENERATION, claim.generation)
        put(HubFields.SCOPE, claim.scope)
        tuple?.let { put(HubFields.TUPLE, it) }
        childPid?.let { put(HubFields.CHILD_PID, it) }
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

    private fun matches(receipt: JsonObject, socket: String, generation: String, tuple: String?): Boolean =
        JsonScalars.str(receipt, HubFields.SOCKET) == socket &&
            (tuple == null || JsonScalars.str(receipt, HubFields.TUPLE) == tuple) &&
            receiptClaim(receipt)?.generation == generation &&
            JsonScalars.long(receipt, HOLDER_EXIT_CONFIRMED_AT) != null

    private fun receiptClaim(body: JsonObject): HubHolderScopeClaim? {
        val generation = JsonScalars.str(body, HubFields.GENERATION)
        val scope = JsonScalars.str(body, HubFields.SCOPE)
        if (generation == null || scope == null) return null
        return HubHolderScopeClaim(generation, scope)
    }

    private fun write(path: Path, body: JsonObject): Boolean = try {
        SecureFile.writeAtomic0600(path, JsonWire.string(body))
        true
    } catch (_: IOException) {
        false
    }
}
