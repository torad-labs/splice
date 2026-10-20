// NEW: Oct 19, 2026, moved verbatim out of PeerReader.kt by concern (ConcentrationLawTest): how `splice peer` prints
// the daemon's answers, and the inbox read it keeps between pages.
package splice.app.cli.peer

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import splice.core.terminal.TerminalOutput
import splice.core.util.JsonScalars
import java.net.URI
import java.net.URISyntaxException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** `--after` and `--limit`, read. */
internal data class InboxWindow(val after: Long, val limit: Long)

/** One inbox read: the key, where to start, the page size, and the switches. */
internal data class InboxRead(val key: String, val after: Long, val limit: Long, val switches: Set<String>)

/** How `splice peer` prints the daemon's answers: one line per peer, one block per journal record. */
internal class PeerLines(private val out: TerminalOutput) {
    private val time = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z").withZone(ZoneId.systemDefault())

    fun peers(body: JsonObject) {
        val peers = (body["peers"] as? JsonArray).orEmpty().map { it.jsonObject }
        if (peers.isEmpty()) out.line("no peers")
        peers.forEach { peer -> out.line(peer(peer)) }
    }

    fun record(record: JsonObject) {
        val seq = JsonScalars.long(record, "seq")
        val at = JsonScalars.long(record, "at")?.let { time.format(Instant.ofEpochMilli(it)) } ?: "?"
        out.line("#$seq  $at  ${headline(record)}")
        JsonScalars.str(record, "text")?.lines()?.forEach { out.line("  $it") }
    }

    fun status(body: JsonObject) {
        out.line(JsonScalars.str(body, "outcome") ?: "unknown")
        val receipt = body["receipt"] as? JsonObject
        if (receipt == null) {
            out.line("no problem reported")
            return
        }
        val at = JsonScalars.long(receipt, "at")?.let { time.format(Instant.ofEpochMilli(it)) } ?: "?"
        val detail = JsonScalars.str(receipt, "detail")?.let { " ($it)" }.orEmpty()
        out.line("${JsonScalars.str(receipt, "status")}$detail at $at")
    }

    fun webhookTest(body: JsonObject): String {
        val status = JsonScalars.long(body, "httpStatus")?.let { "HTTP $it" } ?: "no HTTP answer"
        val detail = JsonScalars.str(body, "detail")?.let { ": $it" }.orEmpty()
        return "${JsonScalars.str(body, "outcome")}, $status$detail"
    }

    private fun peer(peer: JsonObject): String {
        val alive = if (peer["alive"] == JsonPrimitive(true)) "alive" else "down"
        val pid = JsonScalars.long(peer, "pid")?.toString() ?: "-"
        val scope = (peer["sendTo"] as? JsonArray).orEmpty().joinToString(",") { JsonScalars.strOrEmpty(it) }
        val webhook = (peer["webhook"] as? JsonObject)?.let { host(JsonScalars.str(it, "url")) } ?: "none"
        val unread = (peer["inbox"] as? JsonObject)?.let { JsonScalars.long(it, "unread") } ?: 0L
        return "${JsonScalars.str(peer, "name")}  $alive  pid $pid  send-to $scope  webhook $webhook  unread $unread"
    }

    private fun headline(record: JsonObject): String {
        val from = record["from"] as? JsonObject
        return when (JsonScalars.str(record, "kind")) {
            "message" -> "message from ${sender(from)}, auth ${JsonScalars.str(record, "auth") ?: "none"}"
            "receipt" -> receipt(record, from)
            "webhook" -> webhook(record)
            else -> JsonScalars.str(record, "kind") ?: "record"
        }
    }

    private fun receipt(record: JsonObject, from: JsonObject?): String {
        val detail = JsonScalars.str(record, "detail")?.let { " ($it)" }.orEmpty()
        return "receipt ${JsonScalars.str(record, "status")}$detail for ${JsonScalars.str(record, "origMsgId")}" +
            " from ${sender(from)}"
    }

    private fun webhook(record: JsonObject): String {
        val status = JsonScalars.long(record, "httpStatus")?.let { "HTTP $it" } ?: "no HTTP answer"
        return "webhook ${JsonScalars.str(record, "outcome")} for #${JsonScalars.long(record, "ref")} " +
            "($status, ${JsonScalars.long(record, "attempts")} tries)"
    }

    /** `name (registered|unregistered)`; the name a sender claimed is shown only when it differs. */
    private fun sender(from: JsonObject?): String {
        val name = JsonScalars.str(from, "name") ?: JsonScalars.str(from, "address") ?: "unknown"
        val claimed = JsonScalars.str(from, "claimedName")?.takeIf { it != name }?.let { ", claimed $it" }.orEmpty()
        return "$name (${JsonScalars.str(from, "check") ?: "unchecked"}$claimed)"
    }

    /** Only the host of a webhook URL is printed; its path and query may carry a token. */
    private fun host(url: String?): String = try {
        url?.let { URI(it).host } ?: "set"
    } catch (_: URISyntaxException) {
        "set"
    }
}
