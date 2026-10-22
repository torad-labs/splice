package splice.control.mcp.hub

import kotlinx.serialization.json.JsonObject
import splice.control.mcp.JsonRpcCodec
import splice.core.util.JsonScalars
import splice.core.util.JsonWire
import splice.core.util.SecureFile
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

private const val HOLDER_GATES_DIR = "holder-gates"

/** One record at its fixed socket path. The body remains the source of an exact process-generation claim. */
internal data class HubHolderRecord(val path: Path, val body: JsonObject)

private sealed class HubRecordScan {
    data class Readable(val records: List<HubHolderRecord>) : HubRecordScan()

    object Unreadable : HubRecordScan()
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

    /** The gate opens only after the exact launcher identity is durable in its holder record. */
    fun gate(claim: HubHolderScopeClaim): Path = dir.resolve(HOLDER_GATES_DIR).resolve(claim.generation)

    fun releaseGate(claim: HubHolderScopeClaim): Boolean = try {
        Files.createDirectories(gate(claim).parent)
        SecureFile.writeAtomic0600(gate(claim), claim.generation)
        true
    } catch (_: IOException) {
        false
    }

    fun gateReleased(claim: HubHolderScopeClaim): Boolean = try {
        gate(claim).takeIf(Files::isRegularFile)?.let(Files::readString) == claim.generation
    } catch (_: IOException) {
        false
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
                read(path)?.takeIf { body -> JsonScalars.str(body, HubFields.SERVER) != null }
                    ?.let { body -> HubHolderRecord(path, body) }
            }
        }
        return records.takeIf { it.none { record -> record == null } }
            ?.map { requireNotNull(it) }
            ?.let(HubRecordScan::Readable)
            ?: HubRecordScan.Unreadable
    }

    private fun file(key: String): Path = dir.resolve("${key.take(KEY_HEX)}.json")
}
