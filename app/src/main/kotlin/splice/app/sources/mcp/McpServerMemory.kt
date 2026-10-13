// NEW: Oct 12, 2026 — one server's row in GET /api/mcp/memory: its copies now, and one copy if it were shared.
package splice.app.sources.mcp

import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.put

internal class McpServerMemory(private val name: String, private val copies: List<McpCopy>) {
    private val hosted = copies.filter { it.hosted }

    val bytesNow: Long = copies.sumOf { it.bytes }

    /** The hosted copy's RSS where splice hosts the server, else the median copy's: zero with no copy running. */
    val bytesIfShared: Long = when {
        hosted.isNotEmpty() -> hosted.sumOf { it.rss }
        copies.isEmpty() -> 0L
        else -> copies.map { it.rss }.sorted()[copies.size / 2]
    }

    fun into(out: JsonObjectBuilder) {
        out.put("name", name)
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
