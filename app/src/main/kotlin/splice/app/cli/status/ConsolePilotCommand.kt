// NEW: Oct 16, 2026 (Marcos: "Everything that the user can do the agents should be able to do it too") —
// `splice console <action>`: drives the console a person has open, with the taps, words and moves a person makes, so
// an agent such as the splice manager can show and do what it explains. The daemon relays one order to the tab the
// person looked at last (ConsolePilot) and the page acts on itself (console/pilot.js). Bare `splice console` still
// opens the console (ConsoleCommand).
package splice.app.cli.status

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import splice.app.cli.AdminSupport
import splice.app.cli.END_OF_FLAGS
import splice.core.terminal.TerminalOutput
import splice.core.util.JsonScalars
import splice.core.wire.HttpStatus
import splice.daemonclient.ControlPlaneClient
import splice.daemonclient.ControlReply
import splice.daemonclient.MgmtKeyRead
import splice.daemonclient.PostReading

/** The actions `splice console` takes, as its usage line prints them, after the one flag the bare command takes. */
internal const val CONSOLE_PILOT_USAGE: String =
    "[--no-browser | snapshot | read [target] | tap <target> | fill <target> <text> | select <target> <option> | " +
        "press <key> [target] | hover <target> | scroll <target|up|down|top|bottom> | " +
        "highlight <target>... [--note TEXT] | highlight --clear | goto <page|back>] [--json] [-- <words as typed>]"

private const val JSON_FLAG = "--json"
private const val NOTE_FLAG = "--note"
private const val CLEAR_FLAG = "--clear"
private const val HIGHLIGHT = "highlight"

// An invalid action word is a CLI usage error, so it exits with the usage status.
private const val USAGE_EXIT = 2

/** Longer than the daemon's own wait for the page's answer (ConsolePilot), so the daemon's sentence arrives first. */
private const val READ_MS = 30_000

/** The words each action takes after its name: the fewest and the most. */
private val ACTION_WORDS: Map<String, IntRange> = mapOf(
    "snapshot" to 0..0,
    "read" to 0..1,
    "tap" to 1..1,
    "hover" to 1..1,
    "fill" to 2..2,
    "select" to 2..2,
    "press" to 1..2,
    "scroll" to 1..1,
    HIGHLIGHT to 0..Int.MAX_VALUE,
    "goto" to 1..1,
)

internal class ConsolePilotCommand(
    private val out: TerminalOutput = TerminalOutput { println(it) },
    private val err: TerminalOutput = TerminalOutput { System.err.println(it) },
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** 0 when the page did it, 1 when the page or the daemon refused, 2 for words no action takes. */
    internal fun run(args: List<String>): Int {
        // Every word after `--` is the order's own, so a fill can type `--json` or `-h`.
        val flagged = args.takeWhile { it != END_OF_FLAGS }
        val raw = JSON_FLAG in flagged
        val literal = args.dropWhile { it != END_OF_FLAGS }.drop(1)
        val order = order(flagged.filterNot { it == JSON_FLAG }, literal = literal)
        if (order == null) {
            err.line("usage: splice console $CONSOLE_PILOT_USAGE")
            return USAGE_EXIT
        }
        val url = "http://127.0.0.1:${AdminSupport.controlPort()}/api/console/pilot"
        val reading = when (val read = AdminSupport.readMgmtKey()) {
            is MgmtKeyRead.Present -> ControlPlaneClient.post(url, read.key, order, READ_MS)
            is MgmtKeyRead.Absent, is MgmtKeyRead.Unreadable ->
                return refuse("splice holds no readable management key yet, so nothing was done.")
        }
        return when (reading) {
            is PostReading.Answered -> said(reading.reply, raw)
            PostReading.NotConnected -> refuse("the daemon isn't running, so nothing was done.")
            PostReading.CutAfterSending -> refuse(
                "the daemon dropped the connection after the order went up, as a restart does, so it may have " +
                    "run. Run `splice console snapshot` before you try again.",
            )
        }
    }

    /** The order's JSON, or null when the words fit no action. The page reads the words by its action. Flags are read
     *  from [flagged] only; [literal] words, those after `--`, are taken as they are. */
    private fun order(flagged: List<String>, literal: List<String>): String? {
        val action = flagged.firstOrNull() ?: return null
        val rest = flagged.drop(1)
        val noteAt = rest.indexOf(NOTE_FLAG)
        val note = if (noteAt < 0) null else rest.getOrNull(noteAt + 1) ?: return null
        val clear = CLEAR_FLAG in rest
        val words = rest.filterIndexed { i, word -> word != CLEAR_FLAG && (noteAt < 0 || i !in noteAt..noteAt + 1) } +
            literal
        val fits = fits(action, words.size, noted = note != null, clear = clear)
        return if (fits) orderJson(action, words, note, clear) else null
    }

    private fun orderJson(action: String, words: List<String>, note: String?, clear: Boolean): String =
        buildJsonObject {
            put("action", action)
            putJsonArray("words") { words.forEach { add(it) } }
            note?.let { put("note", it) }
            if (clear) put("clear", true)
        }.toString()

    /** Each action takes its own count of words. Only highlight takes --note or --clear, and with --clear it names
     *  nothing and writes no note. */
    private fun fits(action: String, words: Int, noted: Boolean, clear: Boolean): Boolean {
        val counted = ACTION_WORDS[action]?.let { words in it } == true
        return if (action == HIGHLIGHT) {
            counted && clear == (words == 0) && !(clear && noted)
        } else {
            counted && !noted && !clear
        }
    }

    /** What the daemon said: the page's own sentence when it acted, or why nothing was done. */
    private fun said(reply: ControlReply, raw: Boolean): Int {
        val body = JsonScalars.objectOrNull(json, reply.body)
        val done = reply.status in ControlPlaneClient.OK_RANGE && JsonScalars.str(body, "ok") == "true"
        return when {
            reply.status == HttpStatus.NOT_FOUND -> refuse(
                "the daemon that is running is older than this splice and cannot drive the console. " +
                    "Run `splice restart`, then try again.",
            )
            raw -> {
                out.line(reply.body)
                if (done) 0 else 1
            }
            done -> {
                JsonScalars.str(body, "text")?.let(out::line)
                0
            }
            else -> refuse(JsonScalars.str(body, "error") ?: "the console refused that (HTTP ${reply.status}).")
        }
    }

    private fun refuse(sentence: String): Int {
        err.line("splice: $sentence")
        return 1
    }
}
