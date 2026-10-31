// NEW: Oct 19, 2026, moved verbatim out of PeerCommand.kt by concern (ConcentrationLawTest): how `splice peer` reads
// its argv, and the seams its verbs take.
package splice.app.cli.peer

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal const val PEER_USAGE =
    "register <name> [--cwd DIR] [--send-to A,B] [--owner PID] | list [--json] | revoke <name> [--purge] | " +
        "rotate <name> | " +
        "webhook set <name> <url> [--bearer-file F] | webhook clear <name> | webhook test <name> | " +
        "limit <name> <per-minute> <burst> | limit <name> --default | " +
        "send --as <name> [--id ID] [--pid P [--proc-start S]] <to> <text|-> | " +
        "inbox --as <name> [--after N] [--limit N] [--ack] [--json] [--follow] | " +
        "status --as <name> <msgId> [--json] | " +
        "codex attach [<thread>] [--cwd DIR] [--codex-home DIR] [--json] [--hook] | " +
        "codex detach [<thread>] [--purge] [--hook]"

/** One verb's words: the positionals in order, the flags that take a value, and the switches that stand alone. */
internal data class PeerArgs(val words: List<String>, val values: Map<String, String>, val switches: Set<String>) {
    fun forcesHook(): Boolean = HOOK_FLAG in switches
}

internal const val HOOK_FLAG = "--hook"

/** Splits argv into [PeerArgs] against the flags a verb accepts; null on an unknown, repeated or valueless flag. */
internal class PeerArgsParser(private val valued: Set<String>, private val standalone: Set<String>) {
    fun parse(args: List<String>): PeerArgs? {
        val words = mutableListOf<String>()
        val values = mutableMapOf<String, String>()
        val switches = mutableSetOf<String>()
        var i = 0
        while (i < args.size) {
            val word = args[i]
            // How many words this one took: a valued flag takes the next word too; null is an unknown or repeated flag.
            val took = when {
                word in valued -> args.getOrNull(i + 1)?.takeIf { values.put(word, it) == null }?.let { 2 }
                word in standalone -> 1.takeIf { switches.add(word) }
                word.startsWith("--") -> null
                else -> 1.takeIf { words.add(word) }
            }
            i += took ?: return null
        }
        return PeerArgs(words, values, switches)
    }
}

/** Where `send ... -` reads its text: standard input, whole. */
internal fun interface PeerStdin {
    fun read(): String
}

/** One call made with the management key. */
internal fun interface PeerKeyed {
    operator fun invoke(key: String): Int
}

/** The line a successful call prints, from the daemon's answer. */
internal fun interface PeerLine {
    operator fun invoke(reply: JsonObject): String?
}

/** Oct 27, 2026: `send`'s flags. `--id` names the message's msg_id, which makes a repeat answer the first send; `--pid`
 *  (a whole number) and `--proc-start` (only with it) pin the send to the process its target was resolved to. */
internal object PeerSendFlags {
    val VALUED: Set<String> = setOf("--as", "--id", "--pid", "--proc-start")

    fun valid(values: Map<String, String>): Boolean {
        val pid = values["--pid"]
        return if (pid == null) "--proc-start" !in values else pid.toLongOrNull() != null
    }

    fun body(to: String, text: String, values: Map<String, String>): JsonObject = buildJsonObject {
        put("to", to)
        put("text", JsonPrimitive(text))
        values["--id"]?.let { put("id", it) }
        values["--pid"]?.toLongOrNull()?.let { put("pid", it) }
        values["--proc-start"]?.let { put("procStart", it) }
    }
}
