// NEW: v0.4.0 FEATURES.md §8 (8c) — which launch tuples asked their client for roots, remembered across restarts.
// A hosted server that asks depends on its client's roots, which one shared copy cannot have; the planner refuses its
// tuple from then on. Held only in memory, the first session after every restart would wake the server shared, see it
// ask, and lose it mid-work, so the verdict is kept on disk. Each tuple is kept as a SHA-256 digest: an env value is
// often a key, and the file never holds one. A tuple whose command, args or env change is a new digest, probed again.
package splice.client.mcp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import splice.core.util.SecureFile
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** The tuples seen asking for roots; [file] null keeps them in memory only (tests, a host with no state dir). */
public class McpRootsVerdicts(private val file: Path? = null, private val log: LogSink = LogSink {}) {
    private val digests: MutableSet<String> = ConcurrentHashMap.newKeySet<String>().apply { addAll(read()) }

    /** Did [spec]'s launch tuple ever ask its client for roots? */
    public fun asked(spec: McpServerSpec): Boolean = digest(spec) in digests

    /** [spec]'s launch tuple asked for roots: remember it, on disk when there is a file. */
    public fun record(spec: McpServerSpec) {
        if (digests.add(digest(spec))) write()
    }

    private fun read(): Set<String> {
        val path = file?.takeIf { Files.exists(it) } ?: return emptySet()
        return try {
            Files.readAllLines(path).map(String::trim).filter(String::isNotEmpty).toSet()
        } catch (e: IOException) {
            log("splice: could not read the MCP roots verdicts (${SafeFailureText.render(e)}); probing servers again\n")
            emptySet()
        }
    }

    @Synchronized
    private fun write() {
        val path = file ?: return
        try {
            SecureFile.writeAtomic0600(path, digests.sorted().joinToString("\n", postfix = "\n"))
        } catch (e: IOException) {
            log("splice: could not keep the MCP roots verdicts (${SafeFailureText.render(e)}); restarts probe again\n")
        }
    }

    private fun digest(spec: McpServerSpec): String {
        val tuple = JsonArray(
            listOf(
                JsonPrimitive(spec.command),
                JsonArray(spec.args.map(::JsonPrimitive)),
                JsonObject(spec.env.toSortedMap().mapValues { JsonPrimitive(it.value) }),
            ),
        )
        return MessageDigest.getInstance("SHA-256").digest(tuple.toString().toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}
