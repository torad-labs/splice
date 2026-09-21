// NEW: V4-127, FEATURES.md §6 — GET /api/perf/turns?head=&n=&since=, the console's per-turn view.
//
// WHY THIS IS NOT /api/perf WITH A FLAG. That route answers "where did the latency go" by folding a
// tail into per-stage percentiles, and to do it it reads `tailNumeric` — a projection that keeps only
// the values which parse as Long. Every string-and-flag fact the writer stamped on the row (which
// model, which session, which account, whether the cache was cold, whether the turn compacted) is
// dropped by that projection BEFORE any consumer sees it. The console's turn list is made of exactly
// those facts, so it needs the rows, not a summary of their numbers: this route reads the file source
// directly and reports each row whole.
//
// THE PAYLOAD SAYS WHAT IT IS NOT SHOWING. Three numbers ride beside the rows and none of them is
// decoration: `count` is the window's own size, `returned` is the slice actually sent, and
// `truncated` is whether the newest-n clamp cut anything. A payload carrying only the rows cannot
// distinguish "the head was idle" from "the newest n were all that fit", and the console would draw
// the second as the first. The reader's own `read_error` and `skipped_lines` ride through for the
// same reason the summary carries them: a generation that could not be read, or lines this read
// rejected, are missing rows, and a page rendered clean over a holed file is the defect the coverage
// note exists to break.
//
// BOTH LOOKUPS ARE THE SUMMARY'S, NOT COPIES. The default window is `PerfWindow.H24.ms` — the same
// 24h `/api/perf/summary` answers an absent window with — and the default/clamp bounds match the tail
// this family already clamps, so the two routes cannot disagree about what "the last 24 hours" means.
package splice.control.api.usage

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import splice.control.ManagedHead
import splice.control.PerfRow
import splice.control.PerfRowsSource
import splice.control.api.HeadResolver
import splice.core.util.WallClock

/** The durable row key — the one field the writer puts in BOTH the header and the numeric bag. */
private const val TS = "ts"

/** Answered for a head whose owner never wired a perf row source. NOT an empty `rows` array: an empty
 *  list says "this head served no turns", which is a confident false negative about a head that is
 *  serving them right now — the same harm the 400-not-404 rule below prevents, and the same discipline
 *  the doctor route carries for its unwired port. */
private const val UNWIRED_TURNS =
    "the daemon wired no perf row source for this head; /api/perf/turns cannot report its turns"

// why: the row count an absent `?n=` asks for. It mirrors the tail bound /api/perf and /api/logs
// already apply, so no route in this family answers a different number of rows for one request.
private const val DEFAULT_TURNS = 200

// why: the ceiling the clamp holds `?n=` under, at the family's own MAX_TAIL. Clamped rather than
// refused, and the EFFECTIVE value is what the payload reports, so the clamp is never silent.
private const val MAX_TURNS = 2_000

/** The window one request asks for, after validation. A named pair and not a `Pair<Long, Int>`: the
 *  two are read straight into a cutoff and a clamp, and a positional swap of same-shaped values is the
 *  defect the row's own named-argument rule exists for. */
private data class AskedWindow(val since: Long, val n: Int)

internal class PerfRoutes(
    private val resolver: HeadResolver,
    /** Read per request rather than captured: an absent `since` is resolved against NOW, and a
     *  console that has been polling for hours must not keep asking about the hour it started in. */
    private val clock: WallClock = WallClock(System::currentTimeMillis),
) {

    suspend fun turns(call: ApplicationCall) {
        val name = call.request.queryParameters["head"].orEmpty()
        val matches = if (name.isBlank()) emptyList() else resolver.headByName(name)
        if (matches.isEmpty()) {
            // 400 naming the head, never 404 — the console reads 404 on this path as route-not-built,
            // which is the same rule /api/compaction/instructions answers to, for the same console.
            refuse(call, "unknown head: $name", HttpStatusCode.BadRequest)
            return
        }

        val asked = askedWindow(call) ?: return

        val wired = matches.filter { it.perfRows != null }
        if (wired.isEmpty()) {
            refuse(call, UNWIRED_TURNS, HttpStatusCode.ServiceUnavailable)
            return
        }

        call.respondText(
            buildJsonObject {
                put("since", asked.since)
                put("n", asked.n)
                putJsonArray("heads") {
                    matches.forEach { head ->
                        val source = head.perfRows
                        // A head that cannot answer is still LISTED, with its reason in place of its
                        // rows: dropping it would leave a shared wrapper command reporting one head's
                        // turns as if they were both, and the rows carry their own head key precisely
                        // so that the multi-match case never conflates two heads' traffic.
                        if (source == null) {
                            addJsonObject {
                                put("key", head.head.key)
                                put("label", head.head.label)
                                put("error", UNWIRED_TURNS)
                            }
                        } else {
                            add(turnsFor(head, source, asked))
                        }
                    }
                }
            }.toString(),
            ContentType.Application.Json,
        )
    }

    /** The `since`/`n` this request asks for, or null after refusing it. Split out of [turns] because
     *  the request is validated in two independent steps and the route body would otherwise carry four
     *  exits — the wall's point, and the right split: this function answers "what was asked", the
     *  caller answers "what can be served". */
    private suspend fun askedWindow(call: ApplicationCall): AskedWindow? {
        // A `since` that is not a number is REFUSED, never replaced by the default. Substituting 24h
        // for a request the caller spelled differently answers a question nobody asked while looking
        // exactly like an answer to the one that was — the class of defect this route's count/returned
        // fields exist to prevent, committed by the route itself. The family's `tail` helper does
        // substitute its default here; this route deliberately does not.
        val sinceText = call.request.queryParameters["since"]
        val since = if (sinceText == null) clock() - PerfWindow.H24.ms else sinceText.toLongOrNull()
        if (since == null || since < 0) {
            refuse(call, "since must be a non-negative epoch-ms instant, got '$sinceText'", HttpStatusCode.BadRequest)
            return null
        }

        val nText = call.request.queryParameters["n"]
        val requested = if (nText == null) DEFAULT_TURNS else nText.toIntOrNull()
        if (requested == null) {
            refuse(call, "n must be a whole number of rows, got '$nText'", HttpStatusCode.BadRequest)
            return null
        }
        // Clamped rather than refused, and the EFFECTIVE n is what the payload reports: this is the
        // family's own tail bound (/api/perf and /api/logs clamp identically), and a clamp the payload
        // states is not the silent substitution an unparseable `since` would have been.
        return AskedWindow(since, requested.coerceIn(1, MAX_TURNS))
    }

    private fun turnsFor(head: ManagedHead, source: PerfRowsSource, asked: AskedWindow): JsonObject {
        val read = source.window(asked.since)
        val rows = read.rows.takeLast(asked.n)
        return buildJsonObject {
            put("key", head.head.key)
            put("label", head.head.label)
            put("count", read.rows.size)
            put("returned", rows.size)
            put("truncated", read.rows.size > rows.size)
            // The retention evidence, straight from the reader: the oldest timestamp a VALID row holds
            // at all. Null means the source cannot say — never zero, which would read as 1970.
            put("oldest_held_ts", read.oldestHeldTs)
            read.readError?.let { put("read_error", it) }
            if (read.skipped > 0) put("skipped_lines", read.skipped)
            putJsonArray("rows") { rows.forEach { add(rowJson(it)) } }
        }
    }

    /** One row as the console renders it: the numeric bag spread under its own keys, then the named
     *  facts written OVER it, so a key the writer put in both is reported by the typed fact and never
     *  by the bag. `ts` is the only such key today and is skipped from the bag to avoid writing it
     *  twice; the ordering is what makes that a safety net rather than a coincidence. */
    private fun rowJson(row: PerfRow): JsonObject = buildJsonObject {
        row.fields.forEach { (key, value) -> if (key != TS) put(key, value) }
        put(TS, row.ts)
        put("outcome", row.outcome)
        // ABSENT STAYS ABSENT. A null here is a row that never carried the field — `cache_cold` is
        // written only alongside an account, so a row without one never had the question asked — and
        // the JSON null is the third state that keeps the console from rendering "the cache was warm"
        // over a turn where nothing looked. A missing `model` or `compact` is a legacy or torn row and
        // is reported the same way, rather than filled in with a value the file does not contain.
        put("model", row.model)
        put("session", row.session)
        put("account", row.account)
        put("cache_cold", row.cacheCold)
        put("compact", row.compact)
    }

    private suspend fun refuse(call: ApplicationCall, message: String, status: HttpStatusCode) {
        call.respondText(
            buildJsonObject { put("error", message) }.toString(),
            ContentType.Application.Json,
            status,
        )
    }
}
