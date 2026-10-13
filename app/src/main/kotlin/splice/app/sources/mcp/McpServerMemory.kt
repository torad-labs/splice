// NEW: Oct 12, 2026 — one server's row in GET /api/mcp/memory: its copies now, and one copy if it were shared.
package splice.app.sources.mcp

import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.put

internal class McpServerMemory(private val server: McpServerSignature, private val copies: List<McpCopy>) {
    private val hosted = copies.filter { it.hosted }

    val bytesNow: Long = copies.sumOf { it.bytes }

    /** The hosted copy's RSS where splice hosts the server, else the median copy's: zero with no copy running.
     *  Never more than [bytesNow]: one copy shared costs what it costs now, and a copy's RSS counts pages its PSS
     *  splits with others, so a lone copy read 107.5 MB shared against 98 MB now (marlin, desk m, Oct 12). */
    val bytesIfShared: Long = when {
        hosted.isNotEmpty() -> hosted.sumOf { it.rss }
        copies.isEmpty() -> 0L
        else -> copies.map { it.rss }.sorted()[copies.size / 2]
    }.coerceAtMost(bytesNow)

    fun into(out: JsonObjectBuilder) {
        out.put("name", server.name)
        // where it is declared, as GET /api/mcp names a source: the console names the row as its Servers list does
        server.reg?.let { reg ->
            out.put("label", reg.name)
            out.put("kind", reg.kind.name.lowercase())
            out.put("source_file", reg.sourceFile.toString())
            reg.scope?.let { out.put("scope", it.toString()) }
        }
        out.put("web", server.web)
        // how the hub runs it, as /api/mcp words it: null where no plan decides it yet (a project, repo or plugin
        // server)
        out.put("class", server.shareClass?.wire)
        out.put("why", server.why?.key)
        out.put("value", server.why?.value)
        out.put("copies", copies.size)
        out.put("hosted_copies", hosted.size)
        out.put("sessions", copies.filterNot { it.hosted }.map { it.owner }.distinct().size)
        out.put("bytes_now", bytesNow)
        out.put("bytes_if_shared", bytesIfShared)
        val basis = when {
            hosted.isNotEmpty() -> "hosted"
            copies.isEmpty() -> null
            else -> "median_copy"
        }
        out.put("shared_basis", basis)
    }
}
