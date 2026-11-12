// NEW: V4-127, FEATURES.md §6 — GET /api/doctor, the `doctor --json` report over the console.
//
// THE REPORT IS THE CLI'S, VERBATIM. It arrives as JSON TEXT through the DoctorReport port and this
// route writes it out unchanged: the doctor already redacts what it renders (DoctorRedaction, and
// DoctorReportShape scrubs every operator-authored value before the shape pass), so a second serving
// layer that reshaped or trimmed it would be a second redaction policy — and the one thing worse
// than no redaction is two implementations of it disagreeing.
//
// THE STATE-DIRECTORY FOOTPRINT IS PART OF THAT REPORT, not an addition here: DoctorReportShape
// gained stateDirUsage, and the daemon (which can walk the directory) folds it in while building the
// text. Appending it at the route would mean parsing the report to merge into it, which is the
// reshape this file deliberately does not do.
package splice.diagnostics.doctor

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.util.LogSafe
import splice.core.util.LogSink

/** The named reason an unwired doctor route answers 503 rather than an empty report. */
private const val DOCTOR_UNWIRED = "the doctor report is not wired on this daemon"
private const val FIXES_UNWIRED = "doctor fixes are not wired on this daemon"
private const val FIX_PARAM = "id"

/** [log] carries one control line per fix run: the console changed files under the operator's bin
 *  dir, and the daemon log is where that is read back. */
public class DoctorRoute(private val log: LogSink) {
    private val json = Json

    /** POST /api/doctor/fix/{id} (V4-220 item 4). 200 `{fix, report}` when doctor re-run after the
     *  fix finds no row still carrying it; 409 `{error, fix, report}` when the fix refused or rows
     *  remain, [error] one sentence; 404 for an id no fix has; 503 unwired. [report] is the
     *  `doctor --json` object of the run taken after the fix, the same shape GET /api/doctor serves. */
    public suspend fun fix(call: ApplicationCall, fixes: DoctorFixes?, answers: DaemonAnswersSource) {
        if (fixes == null) return refuse(call, FIXES_UNWIRED, HttpStatusCode.ServiceUnavailable)
        val fix = DoctorFix.entries.firstOrNull { it.wire == call.parameters[FIX_PARAM] }
            ?: return refuse(call, unknownFix(), HttpStatusCode.NotFound)
        val outcome = fixes.run(fix, answers())
        val (status, refusal) = when (outcome) {
            is DoctorFixOutcome.Applied -> HttpStatusCode.OK to null
            is DoctorFixOutcome.Refused -> HttpStatusCode.Conflict to outcome.text
        }
        log("[control] doctor fix ${LogSafe.str(fix.wire)}: ${LogSafe.str(refusal ?: "applied")}\n")
        val body = buildJsonObject {
            refusal?.let { put("error", it) }
            put("fix", fix.wire)
            put("report", json.parseToJsonElement(outcome.report))
        }
        call.respondText(body.toString(), ContentType.Application.Json, status)
    }

    private fun unknownFix(): String =
        "no doctor fix by that name; this daemon runs ${DoctorFix.entries.joinToString { it.wire }}"

    private suspend fun refuse(call: ApplicationCall, text: String, status: HttpStatusCode) {
        call.respondText(buildJsonObject { put("error", text) }.toString(), ContentType.Application.Json, status)
    }

    /** [report] ARRIVES AT CALL TIME: the routing lambda reads the server's own property as it calls,
     *  so the port is never captured — a captured one would be null forever against a daemon that
     *  wired it a moment later — and this route holds nothing to capture. A `() -> DoctorReport?`
     *  constructor seam was the unnamed transposable shape kt-no-lambda-seam forbids. [answers] is how
     *  the report reads the daemon it runs in (V4-230): taken here, in process, for this request. */
    public suspend fun doctorJson(call: ApplicationCall, report: DoctorReport?, answers: DaemonAnswersSource) {
        // UNSET IS NOT "NOTHING IS CONFIGURED". V4-136's discipline, carried here: an empty or
        // absent report would tell the operator that a daemon running heads has no findings, which
        // is a confident false negative — the same harm the 400-not-404 rule prevents on the models
        // path. A named 503 says the instrument is missing, which is the truth.
        if (report == null) return refuse(call, DOCTOR_UNWIRED, HttpStatusCode.ServiceUnavailable)
        call.respondText(report(answers()), ContentType.Application.Json)
    }
}
