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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The named reason an unwired doctor route answers 503 rather than an empty report. */
private const val DOCTOR_UNWIRED = "the doctor report is not wired on this daemon"

public class DoctorRoute {

    /** [report] ARRIVES AT CALL TIME: the routing lambda reads the server's own property as it calls,
     *  so the port is never captured — a captured one would be null forever against a daemon that
     *  wired it a moment later — and this route holds nothing to capture. A `() -> DoctorReport?`
     *  constructor seam was the unnamed transposable shape kt-no-lambda-seam forbids. */
    public suspend fun doctorJson(call: ApplicationCall, report: DoctorReport?) {
        // UNSET IS NOT "NOTHING IS CONFIGURED". V4-136's discipline, carried here: an empty or
        // absent report would tell the operator that a daemon running heads has no findings, which
        // is a confident false negative — the same harm the 400-not-404 rule prevents on the models
        // path. A named 503 says the instrument is missing, which is the truth.
        if (report == null) {
            call.respondText(
                buildJsonObject { put("error", DOCTOR_UNWIRED) }.toString(),
                ContentType.Application.Json,
                HttpStatusCode.ServiceUnavailable,
            )
            return
        }
        call.respondText(report(), ContentType.Application.Json)
    }
}
