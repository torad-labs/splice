// NEW: peer messaging (docs/specs/peer-messaging.md section 1) — the frames Splice writes to and reads from Claude Code's
// inbox sockets: the auth line, a peer's message, a receipt, and the decode of what a helper socket receives. Every
// attribute value in an envelope is a name or path Splice validated; the text is defused so it can neither close its
// envelope nor open a second one. The version rule (1.4) replaces the audited list: Claude Code writes `peerProtocol`
// itself, and an exact list broke the feature at every auto-update.
package splice.sessions.note

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.util.JsonScalars
import splice.sessions.peer.DeliveryStatus
import splice.sessions.peer.InboundFrame
import splice.sessions.peer.PeerSender
import splice.sessions.registry.SessionClient

// why: a peer's text is a message, not a file; the spec caps it at 32 KiB after defusing (1.1), in UTF-8 bytes.
internal const val PEER_MAX_TEXT_BYTES: Int = 32_768

// why: one frame per line, and a 32 KiB text with its envelope and JSON escaping fits well inside 64 KiB (1.3).
internal const val PEER_MAX_LINE_BYTES: Int = 65_536

// why: a receipt's detail is a reason, not a body; the spec bounds it at 200 characters (1.2).
private const val MAX_DETAIL_CHARS = 200

// why: the wire version every Claude Code sender from 2.1.282 writes beside its message id (1.1).
private const val WIRE_VERSION = 1

// why: the `peerProtocol` value a registration writes when it speaks this wire (1.4).
private const val PEER_PROTOCOL = 1

// why: 2.1.282 is the oldest Claude Code whose inbox the probe proved takes this frame (tools/probes/claude-code-peer-note.ts).
private const val MIN_CLAUDE_CODE = "2.1.282"

/** Which registrations may be sent to (spec 1.4). Claude Code writes `peerProtocol` itself, so the rule follows each
 *  auto-update where an exact list of versions broke at every one; the probe stays the way a new version is checked. */
internal object PeerVersions {
    private val semver = Regex("^(\\d+)\\.(\\d+)\\.(\\d+)$")

    /** Why a session or peer on [client] may not be sent to, or null when it may: a Claude Code version `X.Y.Z` from
     *  2.1.282, or any other version string, and either way a registration saying `peerProtocol: 1`. */
    fun refusal(client: SessionClient): String? {
        val version = client.version
        val runs = "the session runs ${version?.let { "version $it" } ?: "an unknown version"}"
        return when {
            version.isNullOrBlank() -> "$runs, so its wire is not known"
            olderThanMinimum(version) ->
                "$runs, and messages are sent only to Claude Code $MIN_CLAUDE_CODE or later"
            client.peerProtocol != PEER_PROTOCOL ->
                "$runs, and its registration does not say peerProtocol $PEER_PROTOCOL"
            else -> null
        }
    }

    /** True for an `X.Y.Z` version below [MIN_CLAUDE_CODE]; any other spelling is a non-Claude peer and never older. */
    private fun olderThanMinimum(version: String): Boolean {
        val parts = semver.matchEntire(version)?.groupValues?.drop(1) ?: return false
        val floor = MIN_CLAUDE_CODE.split('.')
        val first = parts.zip(floor).map { (have, need) -> have.toBigInteger().compareTo(need.toBigInteger()) }
            .firstOrNull { it != 0 } ?: 0
        return first < 0
    }
}

/** The envelope a message's content was read out of: the text inside it, and the sender attributes it claimed. */
internal data class EnvelopeParts(val text: String, val fromAddress: String?, val fromName: String?)

internal object PeerWire {
    // why: the element Claude Code wraps every cross-session message in, and the only tag a text is defused against.
    internal const val TAG = "cross-session-message"

    // Every `<` that begins an opening or a closing envelope tag, whatever its case and with whitespace after `<` and `/`.
    private val tagStart = Regex("<(?=\\s*(?:/\\s*)?$TAG)", RegexOption.IGNORE_CASE)
    private val wrapped = Regex("^<$TAG\\b([^>]*)>\\n([\\s\\S]*)\\n</$TAG>$")
    private val attribute = Regex("([A-Za-z][A-Za-z0-9-]*)=\"([^\"]*)\"")
    private val unsafeAttribute = Regex("[\"<>\\r\\n]")
    private val json = Json { ignoreUnknownKeys = true }

    /** [text] with each envelope tag's `<` turned into `<\`, the way Claude Code's own sender defuses a closing one. */
    fun defuse(text: String): String = tagStart.replace(text) { "<\\" }

    /** True when [value] can sit between an attribute's quotes as written. */
    fun attributeSafe(value: String): Boolean = !unsafeAttribute.containsMatchIn(value)

    /** The envelope around [text], defused. [fromAddress] is null for the console, which has no inbox. */
    fun envelope(text: String, fromAddress: String?, fromName: String): String {
        val from = fromAddress?.let { " from=\"$it\"" }.orEmpty()
        return "<$TAG$from from-name=\"$fromName\">\n${defuse(text)}\n</$TAG>"
    }

    /** Why [text] may not be sent, or null when it may: never empty or blank, at most [PEER_MAX_TEXT_BYTES] once defused. */
    fun textRefusal(text: String): String? = when {
        text.isBlank() -> "the text is empty"
        defuse(text).toByteArray(Charsets.UTF_8).size > PEER_MAX_TEXT_BYTES ->
            "a text is at most $PEER_MAX_TEXT_BYTES bytes"
        else -> null
    }

    /** The auth line that opens a connection to a socket whose key file holds [token]. */
    fun encodeAuth(token: String): String = buildJsonObject {
        put("type", "auth")
        put("token", token)
    }.toString() + "\n"

    /** A peer's message to the session [sessionId], from [sender]: `from` and `from-name` both come from the sender. */
    fun encodeMessage(msgId: String, sessionId: String?, text: String, sender: PeerSender): String =
        buildJsonObject {
            put("msgV", WIRE_VERSION)
            put("msg_id", msgId)
            put("type", "user")
            sessionId?.let { put("session_id", it) }
            put("priority", "next")
            put("from", sender.address)
            put(
                "message",
                buildJsonObject {
                    put("role", "user")
                    put("content", JsonPrimitive(envelope(text, sender.address, sender.name)))
                },
            )
        }.toString() + "\n"

    /** The receipt for the message [origMsgId], from [sender]. [detail] is cut to 200 characters. */
    fun encodeReceipt(
        msgId: String,
        origMsgId: String,
        status: DeliveryStatus,
        detail: String?,
        sender: PeerSender,
    ): String = buildJsonObject {
        put("msgV", WIRE_VERSION)
        put("msg_id", msgId)
        put("type", "control")
        put("action", "peer_message_status")
        put("status", status.wire)
        put("orig_msg_id", origMsgId)
        put("from", sender.address)
        detail?.let { put("status_detail", it.take(MAX_DETAIL_CHARS)) }
    }.toString() + "\n"

    /** The token of an auth line, or null when [line] is not one. */
    fun authToken(line: String): String? {
        val obj = JsonScalars.objectOrNull(json, line) ?: return null
        if (JsonScalars.str(obj, "type") != "auth") return null
        return (obj["token"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    }

    /** The frame on [line], or null for every line a helper ignores and counts: not JSON, another type, an unknown
     *  control action or status, or a user frame with no text content. */
    fun decode(line: String): InboundFrame? {
        val obj = JsonScalars.objectOrNull(json, line) ?: return null
        return when (JsonScalars.str(obj, "type")) {
            "user" -> message(obj)
            "control" -> receipt(obj)
            else -> null
        }
    }

    /** The text inside an envelope and the attributes it claimed, or the whole content when it is not one envelope. */
    fun unwrap(content: String): EnvelopeParts {
        val match = wrapped.matchEntire(content) ?: return EnvelopeParts(content, null, null)
        val attrs = attribute.findAll(match.groupValues[1]).associate { it.groupValues[1] to it.groupValues[2] }
        return EnvelopeParts(match.groupValues[2], attrs["from"], attrs["from-name"])
    }

    private fun message(obj: JsonObject): InboundFrame.Message? {
        val content = contentText((obj["message"] as? JsonObject)?.get("content")) ?: return null
        val envelope = unwrap(content)
        return InboundFrame.Message(
            msgId = JsonScalars.str(obj, "msg_id"),
            claimedAddress = JsonScalars.str(obj, "from") ?: envelope.fromAddress,
            claimedName = envelope.fromName,
            text = envelope.text,
        )
    }

    private fun receipt(obj: JsonObject): InboundFrame.Receipt? {
        if (JsonScalars.str(obj, "action") != "peer_message_status") return null
        val wire = JsonScalars.str(obj, "status")
        val status = DeliveryStatus.entries.firstOrNull { it.wire == wire } ?: return null
        val detail = JsonScalars.str(obj, "status_detail") ?: JsonScalars.str(obj, "drop_reason")
        return InboundFrame.Receipt(
            origMsgId = JsonScalars.str(obj, "orig_msg_id"),
            status = status,
            detail = detail?.take(MAX_DETAIL_CHARS),
            claimedAddress = JsonScalars.str(obj, "from"),
        )
    }

    /** A message's content as text: a string, or the text blocks of a block list joined. */
    private fun contentText(content: JsonElement?): String? = when (content) {
        is JsonPrimitive -> content.takeIf { it.isString }?.content
        is JsonArray -> content.mapNotNull { block ->
            (block as? JsonObject)?.takeIf { JsonScalars.str(it, "type") == "text" }
                ?.let { JsonScalars.str(it, "text") }
        }.takeIf { it.isNotEmpty() }?.joinToString("")
        else -> null
    }
}
