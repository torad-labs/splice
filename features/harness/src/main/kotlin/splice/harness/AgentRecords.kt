// NEW: the agent's call (docs/specs/harness.md, decision 12, Kept). The client never saw the calls the model made of a
// capability, so its history holds one assistant message where the provider produced several. splice keeps what the
// provider produced, keyed by the content of the client's copy, on disk before the answer reaches the client, so every
// later request of the session, after a restart too, puts it back byte for byte.
package splice.harness

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import splice.core.parse.lenientJson
import splice.core.util.Cancellables
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import splice.core.util.SecureFile
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.ConcurrentHashMap

/** What the provider produced where the client holds one assistant message, keyed by [key], the content of the
 *  client's copy. [messages] go in its place, in order. [tools] are the capabilities' tools the calls were made of,
 *  which a request that carries them carries too. [beside] is the calls of the last message made beside the client's
 *  own (decision 12, Beside the client's calls), whose results go into the next user message beside the client's;
 *  [leftOut] is a call left out, whose message goes upstream as the client holds it. */
@Serializable
internal data class KeptCall(
    val key: String,
    val messages: List<JsonObject>,
    val tools: List<JsonObject>,
    val beside: List<KeptResult> = emptyList(),
    val leftOut: Boolean = false,
) {
    /** Whether a result beside the client's has not come yet. */
    val waiting: Boolean get() = beside.any { it.block == null }
}

/** One call made beside the client's: its id, its capability, and its result once it came. */
@Serializable
internal data class KeptResult(val id: String, val capability: String, val block: JsonObject? = null)

/** Every call one session keeps. */
@Serializable
internal data class SessionCalls(val calls: List<KeptCall> = emptyList())

/** Each session's kept calls, in memory and written under [dir] (one file a session) when the daemon has one. */
internal class AgentRecords(private val dir: Path?, private val log: LogSink) {
    private val sessions = ConcurrentHashMap<String, SessionCalls>()

    /** Session [session]'s calls, by key. */
    fun of(session: String): Map<String, KeptCall> =
        sessions.computeIfAbsent(session) { read(it) ?: SessionCalls() }.calls.associateBy { it.key }

    /** Keeps [call] for [session], in place of one of the same key: on disk before it returns. */
    fun put(session: String, call: KeptCall) {
        val next = checkNotNull(
            sessions.compute(session) { key, prior ->
                val calls = (prior ?: read(key) ?: SessionCalls()).calls.filter { it.key != call.key }
                SessionCalls(calls + call)
            },
        )
        val path = file(session) ?: return
        SecureFile.writeAtomic0600(path, lenientJson.encodeToString(SessionCalls.serializer(), next))
    }

    private fun file(session: String): Path? = dir?.resolve("${digest(session)}.json")

    private fun read(session: String): SessionCalls? {
        val path = file(session)?.takeIf(Files::isRegularFile) ?: return null
        return Cancellables.runCatchingCancellable {
            lenientJson.decodeFromString(SessionCalls.serializer(), Files.readString(path))
        }.onFailure {
            log("[harness] a session's kept calls could not be read: ${SafeFailureText.render(it)}\n")
        }.getOrNull()
    }

    private fun digest(text: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)))
}
