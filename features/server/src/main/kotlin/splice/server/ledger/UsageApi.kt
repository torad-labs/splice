// NEW: server mode (docs/specs/server-mode.md, F5). The ledger's operations, as each door asks them: the CLI
// through the control plane, the control plane itself, and an admin's key on the listener. Each reads the same query names:
//
//   rows     since | from, to; person                 every row of the window, oldest first: {"window", "rows"}
//   report   since | from, to; by; format             the window's rows grouped `by` person (the default), team,
//                                                     current-team, day, model or device, as json (the default),
//                                                     csv or md; the json also carries what the providers paid
//                                                     for by the hour cost over the window, and its totals (F10)
package splice.server.ledger

import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.topology.PersonEmails
import splice.core.topology.ServerConfig
import splice.server.PERSON
import splice.server.ServerAnswer
import splice.server.ServerPeople
import splice.server.UpstreamApi
import java.time.Clock

public class UsageApi internal constructor(
    private val ledger: UsageLedger,
    people: ServerPeople,
    server: ServerConfig,
    clock: Clock,
    /** The providers behind the server, for what those paid for by the hour cost (F10). */
    private val upstreams: UpstreamApi,
    /** When the host came up, read at each report: the kernel's boot time. */
    boot: HostBoot = KernelBoot(),
) {
    private val windows = UsageWindows(clock)
    private val report = UsageReport(people, server.teams, server.reference)
    private val fixed = FixedMeter(boot, clock)
    private val json = Json { encodeDefaults = true }

    /** The rows of the window [asked] names, only one person's when it names `person`. */
    public fun rows(asked: Parameters): ServerAnswer = rowsAsked(query(asked))

    /** The report [asked] names, in the format it names. */
    public fun report(asked: Parameters): ServerAnswer = reportAsked(query(asked))

    private fun rowsAsked(query: Map<String, String>): ServerAnswer = inWindow(query) { window ->
        val asked = query[PERSON]
        val person = asked?.let(PersonEmails::read)
        if (asked != null && person == null) refused(NOT_AN_EMAIL) else rowsOf(window, person)
    }

    private fun rowsOf(window: UsageWindow, person: String?): ServerAnswer {
        val rows = ledger.between(window.from, window.to, person)
        val body = buildJsonObject {
            put("window", window.json())
            put("rows", buildJsonArray { rows.forEach { add(json.encodeToJsonElement(LedgerRow.serializer(), it)) } })
        }
        return ServerAnswer.Done(HttpStatusCode.OK, body)
    }

    private fun reportAsked(query: Map<String, String>): ServerAnswer = inWindow(query) { window ->
        val by = ReportBy.entries.firstOrNull { it.wire == (query[BY] ?: ReportBy.PERSON.wire) }
        val format = ReportFormat.entries.firstOrNull { it.wire == (query[FORMAT] ?: ReportFormat.JSON.wire) }
        when {
            by == null -> refused("by must be one of ${ReportBy.entries.joinToString(", ") { it.wire }}")
            format == null -> refused("format must be one of ${ReportFormat.entries.joinToString(", ") { it.wire }}")
            else -> reportOf(window, by, format)
        }
    }

    private fun reportOf(window: UsageWindow, by: ReportBy, format: ReportFormat): ServerAnswer {
        val body = report.of(ledger.between(window.from, window.to), window, by, fixed.of(upstreams.hourly(), window))
        if (format == ReportFormat.JSON) return ServerAnswer.Done(HttpStatusCode.OK, body)
        return ServerAnswer.Text(HttpStatusCode.OK, format.contentType, UsageTables.text(body, format))
    }

    /** [answer] for the window [query] names, or the refusal of one it cannot read. */
    private inline fun inWindow(query: Map<String, String>, answer: (UsageWindow) -> ServerAnswer): ServerAnswer =
        when (val read = windows.of(query)) {
            is WindowReading.Read -> answer(read.window)
            is WindowReading.Unread -> refused(read.why)
        }

    /** Each name [asked] carries, with the first value it gives it. */
    private fun query(asked: Parameters): Map<String, String> = asked.names().associateWith { asked[it].orEmpty() }

    private fun refused(why: String): ServerAnswer = ServerAnswer.Refused(HttpStatusCode.BadRequest, why)
}

// The query's names.
private const val BY = "by"
private const val FORMAT = "format"

private const val NOT_AN_EMAIL = "person must be an email, as in ada@team.example"
