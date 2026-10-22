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

internal const val HOLDER_EXIT_CONFIRMED_AT = "exit_confirmed_at"
private const val HOLDER_EXITS_DIR = "holder-exits"

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
