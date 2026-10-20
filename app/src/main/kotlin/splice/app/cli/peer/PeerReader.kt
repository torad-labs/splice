package splice.app.cli.peer

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import splice.core.terminal.TerminalOutput
import splice.core.util.JsonScalars
import splice.upstream.Ticker
import splice.upstream.codemode.ProcessTicker
import java.net.URI
import java.net.URISyntaxException
import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// why: spec 5, `inbox --follow` polls every 2 s: quick enough to read as live, and one loopback GET each time.
private const val FOLLOW_INTERVAL_MS = 2_000L

// why: an inbox read without --limit sends none, and the daemon's own default page size applies.
private const val NO_LIMIT = 0L

/** The `--as` verbs that read: `inbox` and `status`. */
internal class PeerReader(
    private val call: PeerCall,
    private val out: TerminalOutput,
    private val err: TerminalOutput,
    private val ticker: Ticker = ProcessTicker(),
) {
    private val lines = PeerLines(out)

    /** `inbox --as <name> [--after N] [--limit N] [--ack] [--json] [--follow]`. */
    suspend fun inbox(args: List<String>): Int? {
        val parsed = PeerArgsParser(setOf("--as", "--after", "--limit"), setOf("--ack", "--json", "--follow"))
            .parse(args)?.takeIf { it.words.isEmpty() }
        val window = parsed?.let { window(it.values) }
        if (parsed == null || window == null) return null
        val key = call.peerKey(parsed.values["--as"]) ?: return PeerExit.REFUSED
        return poll(InboxRead(key, window.after, window.limit, parsed.switches))
    }

    /** `status --as <name> <msgId>`: the send's outcome, then its receipt or `no problem reported`. */
    fun status(args: List<String>): Int? {
        val parsed = PeerArgsParser(setOf("--as"), emptySet()).parse(args)
        val msgId = parsed?.words?.singleOrNull() ?: return null
        val key = call.peerKey(parsed.values["--as"]) ?: return PeerExit.REFUSED
        val path = "/api/peers/self/sent/${URLEncoder.encode(msgId, Charsets.UTF_8)}"
        return when (val answer = call.call("GET", path, key)) {
            is PeerAnswer.Exit -> answer.code
            is PeerAnswer.Ok -> {
                lines.status(answer.body)
                PeerExit.DONE
            }
        }
    }

    private suspend fun poll(read: InboxRead): Int {
        var after = read.after
        var code: Int? = null
        while (code == null) {
            when (val answer = page(read, after)) {
                is PeerAnswer.Exit -> code = answer.code
                is PeerAnswer.Ok -> {
                    val next = JsonScalars.long(answer.body, "next") ?: after
                    code = show(read, answer.body, next)
                    after = next
                    if (code == null && !ticker.awaitTick(FOLLOW_INTERVAL_MS)) code = PeerExit.DONE
                }
            }
        }
        return code
    }

    /** Where a read starts and how many it asks for, or null when either flag is not a whole number it accepts. */
    private fun window(values: Map<String, String>): InboxWindow? {
        val after = whole(values["--after"], absent = 0L, least = 0L)
        val limit = whole(values["--limit"], absent = NO_LIMIT, least = 1L)
        return if (after == null || limit == null) null else InboxWindow(after, limit)
    }

    /** [absent] when the flag is missing; null when it is there but not a whole number of at least [least]. */
    private fun whole(text: String?, absent: Long, least: Long): Long? =
        if (text == null) absent else text.toLongOrNull()?.takeIf { it >= least }

    private fun page(read: InboxRead, after: Long): PeerAnswer {
        val query = buildString {
            append("/api/peers/self/inbox?after=").append(after)
            if (read.limit != NO_LIMIT) append("&limit=").append(read.limit)
        }
        return call.call("GET", query, read.key)
    }

    /** Prints one page and acks it when asked; the exit code when the read is over, or null to keep following. */
    private fun show(read: InboxRead, page: JsonObject, next: Long): Int? {
        val items = (page["items"] as? JsonArray).orEmpty().map { it.jsonObject }
        if ("--json" in read.switches) out.line(page.toString()) else items.forEach { lines.record(it) }
        val refused = if ("--ack" in read.switches && items.isNotEmpty()) acknowledge(read.key, next) else null
        return refused ?: if ("--follow" in read.switches) null else PeerExit.DONE
    }

    /** Marks everything through [through] read; an exit code when the daemon refused. */
    private fun acknowledge(key: String, through: Long): Int? {
        val body = buildJsonObject { put("through", through) }
        return when (val answer = call.call("POST", "/api/peers/self/inbox/ack", key, body)) {
            is PeerAnswer.Exit -> answer.code
            is PeerAnswer.Ok -> {
                if (JsonScalars.long(answer.body, "read") == null) err.line("splice peer: the ack was not confirmed")
                null
            }
        }
    }
}

/** `--after` and `--limit`, read. */
private data class InboxWindow(val after: Long, val limit: Long)

/** One inbox read: the key, where to start, the page size, and the switches. */
private data class InboxRead(val key: String, val after: Long, val limit: Long, val switches: Set<String>)

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
