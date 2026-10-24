// NEW: LAN share, phase 2, milestone 4 (docs/specs/lan-share.md). How the Sharing page's rows answer when they run a
// `splice share` or `splice remote` verb: the lines it printed, without their colours or their verb, each marked when
// it was a refusal, and whether the change it made waits on a restart the page then takes.
package splice.app.control.api.share

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import splice.app.cli.share.REMOTE_VERB
import splice.configuration.add.DaemonRestart
import splice.core.terminal.TerminalOutput
import splice.http.JsonReply

/** The lines a verb printed, for the page: a line under the verb's own name is a refusal. */
internal class Said {
    private val lines = mutableListOf<Pair<String, Boolean>>()
    private val ansi = Regex("\u001B\\[[0-9;]*m")

    val terminal = TerminalOutput { line -> keep(line) }

    fun bad(line: String) {
        lines += line to true
    }

    private fun keep(raw: String) {
        val line = raw.replace(ansi, "").trim()
        val verb = listOf("$REMOTE_VERB: ", "$SHARE_VERB: ").firstOrNull { line.startsWith(it) }
        if (line.isNotEmpty()) lines += (verb?.let { line.removePrefix(it) } ?: line) to (verb != null)
    }

    fun refused(): JsonReply = answer(ok = false, restart = false)

    fun answer(ok: Boolean, restart: Boolean): JsonReply {
        val body = buildJsonObject {
            put("ok", ok)
            put("restart", restart)
            putJsonArray("said") {
                lines.forEach { (text, refusal) ->
                    addJsonObject {
                        put("text", text)
                        put("bad", refusal)
                    }
                }
            }
        }
        return JsonReply(HttpStatusCode.OK, body.toString())
    }
}

/** The page's restart: a change that needs one is marked due, and the page takes the console's restart for it. */
internal class DueRestart : DaemonRestart {
    var due = false
        private set

    override fun invoke(): Boolean {
        due = true
        return true
    }
}
