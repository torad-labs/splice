// NEW: MCP hub step 4, "started when used" — the kept listing the front answers a session's initialize and tools/list
// from, with no holder started. One file per declared server, at <state>/mcp-listings/<digest>.json, named by the
// SHA-256 of its declared command, arguments and environment (HubTuple.digest), so every session of one server reads
// one listing and no value is in a file name. The front posts what a fresh holder answered (its initialize result and
// its tools/list result) and posts again on notifications/tools/list_changed; this writes it. A post that carries no
// tools keeps the kept ones only while the server names the same serverInfo: another name or version is another
// package, whose tools are not the kept ones (splice-config's rule 1, Oct 12).
package splice.control.mcp.hub

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import splice.control.mcp.JsonRpcCodec
import splice.core.util.JsonWire
import splice.core.util.LogSafe
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import splice.core.util.SecureFile
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

private const val INITIALIZE = "initialize"
private const val HUB_LISTING_TOOLS = "tools"

// why: the initialize field naming the server and its version, which tells a new package from the kept one.
private const val SERVER_INFO = "serverInfo"

internal class HubListings(private val dir: Path, private val log: LogSink) {
    /** Where [digest]'s listing is kept, or null when nothing is kept for it yet. */
    fun kept(digest: String): Path? = file(digest).takeIf { Files.isRegularFile(it) }

    /** What a fresh holder answered. [tools] null keeps the kept tools only under the same serverInfo. */
    @Synchronized
    fun keep(digest: String, initialize: JsonObject, tools: JsonObject?) {
        val before = read(digest)
        val sameServer = (before[INITIALIZE] as? JsonObject)?.get(SERVER_INFO) == initialize[SERVER_INFO]
        val keptTools = tools ?: (before[HUB_LISTING_TOOLS] as? JsonObject)?.takeIf { sameServer }
        val body = buildJsonObject {
            put(INITIALIZE, initialize)
            keptTools?.let { put(HUB_LISTING_TOOLS, it) }
        }
        try {
            SecureFile.writeAtomic0600(file(digest), JsonWire.string(body))
        } catch (e: IOException) {
            log(
                "[mcp-hub] could not keep a server's listing (${LogSafe.str(SafeFailureText.render(e))}); " +
                    "its next session starts it to list\n",
            )
        }
    }

    private fun read(digest: String): JsonObject {
        val path = kept(digest) ?: return JsonObject(emptyMap())
        return try {
            JsonRpcCodec().parse(Files.readString(path)) ?: JsonObject(emptyMap())
        } catch (_: IOException) {
            JsonObject(emptyMap())
        }
    }

    private fun file(digest: String): Path = dir.resolve("$digest.json")
}
