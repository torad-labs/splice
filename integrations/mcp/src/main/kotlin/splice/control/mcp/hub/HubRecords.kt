// NEW: Oct 13 — the holder records in <state>/mcp-hub, read apart from the front's own files there. The front keeps
// front.json and front-exec.json in the same folder, and the sweep, listing every .json as a holder's record, took each
// for an orphaned shared holder five minutes into a daemon and deleted it. A record is a file named by its holder's key
// that names its server.
package splice.control.mcp.hub

import kotlinx.serialization.json.JsonObject
import splice.control.mcp.JsonRpcCodec
import splice.core.util.JsonScalars
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

// why: a holder's socket and record are named by its key's first KEY_HEX hex digits, short enough for a Unix socket path
internal const val KEY_HEX = 16

internal class HubRecords(private val dir: Path) {
    private val named = Regex("[0-9a-f]{$KEY_HEX}\\.json")

    /** Every holder record, each with what it says. */
    fun all(): List<Pair<Path, JsonObject>> {
        if (!Files.isDirectory(dir)) return emptyList()
        val files = Files.list(dir).use { paths -> paths.filter { named.matches(it.fileName.toString()) }.toList() }
        return files.mapNotNull { record ->
            read(record)?.takeIf { JsonScalars.str(it, "server") != null }?.let { record to it }
        }
    }

    /** Each recorded holder's socket, by the server its record names. */
    fun serverBySocket(): Map<String, String> = all().mapNotNull { (record, body) ->
        JsonScalars.str(body, "server")?.let { socketOf(record).toString() to it }
    }.toMap()

    fun socketOf(record: Path): Path = record.resolveSibling(record.fileName.toString().removeSuffix(".json") + ".sock")

    fun read(record: Path): JsonObject? = try {
        record.takeIf(Files::isRegularFile)?.let { JsonRpcCodec().parse(Files.readString(it)) }
    } catch (_: IOException) {
        null
    }
}
