// NEW: server mode (docs/specs/server-mode.md, F5): `splice server usage rows|report`, the operator's reading of the
// server's usage ledger, over `--since <duration>` or `--from <instant> --to <instant>`.
//
//   usage rows [--person <email>] [--json]                   every request of the window, oldest first
//   usage export --now                                       copy the ledger to [server.export] now
//   usage report [--by person|team|current-team|day|model|device] [--format json|csv|md]
//                                                            the window's requests, tokens and cost by group
//
// A report is read from the daemon as JSON and drawn here in the format asked (UsageTables), so it is the same text the
// control plane answers for that format.
package splice.app.cli.server

import kotlinx.serialization.json.JsonObject
import splice.app.cli.peer.ControlRequest
import splice.app.cli.peer.JSON_SWITCH
import splice.app.cli.peer.PeerArgsParser
import splice.app.cli.peer.PeerAsk
import splice.server.ledger.ReportFormat
import splice.server.ledger.UsageTables

internal class ServerUsageVerbs(private val ask: PeerAsk) {
    private val lines = UsageLines()

    /** `usage rows [--since D | --from T --to T] [--person <email>] [--json]` */
    fun rows(args: List<String>): Int? {
        val parsed = PeerArgsParser(WINDOW_FLAGS + PERSON_FLAG, setOf(JSON_SWITCH)).parse(args)
            ?.takeIf { it.words.isEmpty() } ?: return null
        val asked = ControlRequest("GET", "$USAGE_PATH/rows${query(parsed.values)}")
        return ask.managed(asked, JSON_SWITCH in parsed.switches) { lines.rows(it) }
    }

    /** `usage report [--by K] [--since D | --from T --to T] [--format json|csv|md]`; a terminal gets the md table. */
    fun report(args: List<String>): Int? {
        val parsed = PeerArgsParser(WINDOW_FLAGS + BY_FLAG + FORMAT_FLAG, emptySet()).parse(args)
            ?.takeIf { it.words.isEmpty() } ?: return null
        val wire = parsed.values[FORMAT_FLAG] ?: ReportFormat.MD.wire
        val format = ReportFormat.entries.firstOrNull { it.wire == wire } ?: return null
        val asked = ControlRequest("GET", USAGE_PATH + query(parsed.values - FORMAT_FLAG))
        return ask.managed(asked) { UsageTables.text(it, format) }
    }

    /** `usage export --now`: copies the ledger to `[server.export]` at once, and ends once every write is answered. */
    fun export(args: List<String>): Int? {
        if (args != listOf(NOW_SWITCH)) return null
        val asked = ControlRequest("POST", "$USAGE_PATH/export", JsonObject(emptyMap()), LEDGER_EXPORT_WAIT_MS)
        return ask.managed(asked) { lines.exported(it) }
    }

    /** [flags] as the control plane's query: `?since=1h&person=...`, each value escaped; empty for none. */
    private fun query(flags: Map<String, String>): String {
        if (flags.isEmpty()) return ""
        return flags.entries.joinToString("&", "?") { (flag, value) ->
            "${flag.removePrefix("--")}=${ask.segment(value)}"
        }
    }
}

internal const val USAGE_PATH = "/api/server/usage"
private val WINDOW_FLAGS = setOf("--since", "--from", "--to")
private const val BY_FLAG = "--by"
private const val FORMAT_FLAG = "--format"
private const val NOW_SWITCH = "--now"

// why: the daemon answers an export once every hour's file is written. A first export of months of rows to a bucket is
// hundreds of PUTs, and the daemon already lets a hung store go (a minute per PUT), so the CLI waits as long as a long
// export takes rather than calling one that is still writing a failure.
private const val LEDGER_EXPORT_WAIT_MS = 30 * 60_000
