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
// decoration: `count` is the rows of the window the filters match (V4-444, [TurnsFilter]; with no
// filter, the window's own size), `returned` is the slice actually sent, and
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
package splice.usage.perf

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.parseQueryString
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import splice.core.model.TurnPrice
import splice.core.perf.PerfKeys
import splice.core.util.WallClock
import splice.usage.USAGE_READ_PENDING_HEADER
import splice.usage.UsageHead
import splice.usage.UsageHeadLookup
import splice.usage.UsageReadPreparation
import java.time.DateTimeException
import java.time.ZoneId

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

/** Which whole-window figures the answer carries beside its rows. [peak] is the most requests held at once over
 *  EVERY matching row, not the clamped newest. */
private data class AskedFigures(val usage: Boolean = true, val peak: Boolean = false, val names: Boolean = false) {
    /** The usage block this read asked for: `usage=names` is the menus' read, the names alone without the priced fold
     *  (TurnNames); `usage=0` leaves the block out. */
    fun usageJson(matching: List<PerfRow>, gaps: TurnPriceGap, zone: ZoneId, reading: AccountReading): JsonObject? =
        when {
            names -> TurnNames(matching, reading).json()
            usage -> TurnUsage(matching, gaps, zone, reading).json()
            else -> null
        }
}

/** `?order=`: the n rows a clamp keeps. `price` ranks the WHOLE matching window by what each request costs at the
 *  head's published API rates (a key's cost_usd, a plan's plan_value_usd), unpriced requests last, ties newest first. */
private enum class TurnsOrder(val wire: String) {
    NEWEST("newest"),
    PRICE("price"),
}

/** The rows the clamp keeps: [n] of them, and which [n] of the matching window, the newest or the highest priced. */
private data class AskedRows(val n: Int, val order: TurnsOrder = TurnsOrder.NEWEST)

/** The window one request asks for, after validation. A named pair and not a `Pair<Long, Int>`: the
 *  two are read straight into a cutoff and a clamp, and a positional swap of same-shaped values is the
 *  defect the row's own named-argument rule exists for. */
private data class AskedWindow(
    val since: Long,
    val rows: AskedRows,
    val filter: TurnsFilter,
    val zone: ZoneId,
    val figures: AskedFigures = AskedFigures(),
    /** More questions of the SAME window, each its own filter and clamp, answered from the one read of it. */
    val asks: List<AskedWindow> = emptyList(),
    /** The project snapshot this request's rows are tagged from. */
    val tags: ProjectTags = NoProjectTags,
)

public class PerfRoutes(
    private val heads: UsageHeadLookup,
    /** Read per request rather than captured: an absent `since` is resolved against NOW, and a
     *  console that has been polling for hours must not keep asking about the hour it started in. */
    private val clock: WallClock = WallClock(System::currentTimeMillis),
) {
    private val readFilter = TurnsFilterReader()

    /** Installed by the application before serving routes; older readers never consult it. */
    public var preparation: UsageReadPreparation? = null

    /** Installed by the application: which project each request's session belongs to, taken once per request. */
    public var projects: RequestProjects = RequestProjects { NoProjectTags }

    /** Installed by the application: how each head's recorded account values read (labels only, Oct 12). The default
     *  leaves every value as recorded. */
    public var accounts: RecordedAccounts = RecordedAccounts { HeadAccountNames { null } }

    public suspend fun turns(call: ApplicationCall) {
        val name = call.request.queryParameters["head"].orEmpty()
        val matches = if (name.isBlank()) emptyList() else heads.byName(name).ifEmpty { heads.retired(name) }
        if (matches.isEmpty()) {
            // 400 naming the head, never 404 — the console reads 404 on this path as route-not-built,
            // which is the same rule /api/compaction/instructions answers to, for the same console.
            refuse(call, "unknown head: $name", HttpStatusCode.BadRequest)
            return
        }

        val tags = RequestedTags(projects)
        val asked = askedWindow(call, tags) ?: return

        val wired = matches.filter { it.sinks.perfRows != null }
        if (wired.isEmpty()) {
            refuse(call, UNWIRED_TURNS, HttpStatusCode.ServiceUnavailable)
            return
        }
        // a head still preparing answers pending, decided first: its reply reads no row, so it asks neither the roster
        // nor the session registry (a pending reply that built both waited 27 s on desk d in the startup scan, Oct 12)
        val pending = preparation?.takeIf { call.request.headers[USAGE_READ_PENDING_HEADER] == "1" }
            ?.requestsPending(wired, asked.since).orEmpty()
        val read = wired.filter { it.key !in pending }
        // taken here, outside every head's row lock, rather than by the first row that asks
        if (read.isNotEmpty()) tags.take()
        // read before the body is built: naming a login asks the roster, which suspends
        val names = read.associate { it.key to accounts.forHead(it.key) }

        call.respondText(
            buildJsonObject {
                put("since", asked.since)
                put("n", asked.rows.n)
                putJsonArray("heads") {
                    matches.forEach { head ->
                        val source = head.sinks.perfRows
                        // A head that cannot answer is still LISTED, with its reason in place of its
                        // rows: dropping it would leave a shared wrapper command reporting one head's
                        // turns as if they were both, and the rows carry their own head key precisely
                        // so that the multi-match case never conflates two heads' traffic.
                        if (source == null) {
                            addJsonObject {
                                put("key", head.key)
                                put("label", head.label)
                                put("error", UNWIRED_TURNS)
                            }
                        } else if (head.key in pending) {
                            addJsonObject {
                                put("key", head.key)
                                put("label", head.label)
                                put("read_pending", true)
                                putJsonArray("rows") { }
                            }
                        } else {
                            add(turnsFor(head, source, asked, names.getValue(head.key)))
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
    private suspend fun askedWindow(call: ApplicationCall, tags: ProjectTags): AskedWindow? {
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

        val zoneText = call.request.queryParameters["time_zone"] ?: "UTC"
        val zone = try {
            ZoneId.of(zoneText)
        } catch (_: DateTimeException) {
            refuse(call, "unknown time_zone: $zoneText", HttpStatusCode.BadRequest)
            return null
        }
        val asks = call.request.queryParameters.getAll("ask").orEmpty()
            .map { askedOf(parseQueryString(it), since, zone, tags) }
        val asked = askedOf(call.request.queryParameters, since, zone, tags)
        val unread = (listOf(asked) + asks).firstOrNull { it.refusal != null }
        unread?.let { refuse(call, it.refusal.orEmpty(), HttpStatusCode.BadRequest) }
        return asked.window?.copy(asks = asks.mapNotNull { it.window })?.takeIf { unread == null }
    }

    /** One question's window, or the sentence naming what it could not read. */
    private data class AskRead(val window: AskedWindow?, val refusal: String? = null)

    private fun askedOf(params: Parameters, since: Long, zone: ZoneId, tags: ProjectTags): AskRead {
        val nText = params["n"]
        val read = readFilter(params)
        val filter = (read as? TurnsFilterRead.Read)?.filter?.inProject(params["project"], tags)
        val orderText = params["order"]
        val order =
            if (orderText == null) TurnsOrder.NEWEST else TurnsOrder.entries.firstOrNull { it.wire == orderText }
        val window = rowsAsked(nText)?.let { n ->
            filter?.takeIf { order != null }?.let {
                val usage = params["usage"]
                val figures = AskedFigures(usage != "0", params["peak"] == "1", usage == "names")
                AskedWindow(since, AskedRows(n, order ?: TurnsOrder.NEWEST), it, zone, figures, tags = tags)
            }
        }
        return AskRead(window, refusal(nText, read, orderText, order).takeIf { window == null })
    }

    /** The rows `?n=` asks for, or null when it is not a whole number. Clamped rather than refused, and the
     *  EFFECTIVE n is what the payload reports: this is the family's own tail bound (/api/perf and /api/logs
     *  clamp identically), and a clamp the payload states is not the silent substitution an unparseable
     *  `since` would have been. */
    private fun rowsAsked(nText: String?): Int? =
        if (nText == null) DEFAULT_TURNS else nText.toIntOrNull()?.coerceIn(1, MAX_TURNS)

    /** Why a request whose `n` or filters could not be read is refused, naming the parameter. */
    private fun refusal(nText: String?, read: TurnsFilterRead, orderText: String?, order: TurnsOrder?): String =
        if (rowsAsked(nText) == null) {
            "n must be a whole number of rows, got '$nText'"
        } else if (order == null) {
            "order must be newest or price, got '$orderText'"
        } else {
            (read as? TurnsFilterRead.Refused)?.message.orEmpty()
        }

    private fun turnsFor(
        head: UsageHead,
        source: PerfRowsSource,
        asked: AskedWindow,
        names: HeadAccountNames,
    ): JsonObject {
        if (source is ProjectedPerfRowsSource) {
            val gaps = decisions(head)
            return source.projected(asked.since) { projection -> turnsJson(head, projection, asked, names, gaps) }
        }
        val read = source.window(asked.since)
        return turnsJson(
            head,
            object : PerfRowsProjection {
                override val window: PerfRowsWindow = read
                override fun complete(rows: List<PerfRow>): List<PerfRow> = rows
            },
            asked,
            names,
            decisions(head),
        )
    }

    /** One classification for a read's counters and for each row's own figure (TurnPriceGap), priced on one catalog
     *  snapshot: live() asks splice.toml's stamp, and asking it per row was a file stat per priced row (jstack on desk c,
     *  Oct 12). */
    private fun decisions(head: UsageHead): TurnPriceGap =
        TurnPriceGap(head.statusline.catalog?.live()?.let(::TurnPrice), AccountPlans(head, heads.billing(head.key)))

    private fun turnsJson(
        head: UsageHead,
        projection: PerfRowsProjection,
        asked: AskedWindow,
        names: HeadAccountNames,
        /** The read's pricing decisions, shared with every further question of it, so an ask prices no row twice. */
        gaps: TurnPriceGap,
    ): JsonObject {
        val read = projection.window
        // an account filter asks by ref, and a row reads its ref and label, whatever value the row was written with
        val filter = asked.filter.reading(names)
        val reading = AccountReading(names)
        // The filters narrow the WINDOW, then the clamp takes the newest of what matched: a filter run
        // after the clamp finds nothing older than the slice (V4-444).
        val matching = read.rows.filter(filter::matches)
        val rows = projection.complete(kept(matching, asked.rows, gaps))
        return buildJsonObject {
            put("key", head.key)
            put("label", head.label)
            put("order", asked.rows.order.wire)
            put("count", matching.size)
            put("returned", rows.size)
            put("truncated", matching.size > rows.size)
            // The retention evidence, straight from the reader: the oldest timestamp a VALID row holds
            // at all. Null means the source cannot say — never zero, which would read as 1970.
            put("oldest_held_ts", read.oldestHeldTs)
            read.readError?.let { put("read_error", it) }
            if (read.windowSkipped > 0) put("skipped_lines", read.windowSkipped)
            // `usage=0` leaves the block out: it is a fold over every matching row, and a caller that reads only
            // `count` and `rows` (Settings asks about a hundred such counts) must not pay for figures it drops.
            val usage = asked.figures.usageJson(matching, gaps, asked.zone, reading)
            if (usage != null) put("usage", usage)
            // Over every row of the window the filters matched, never the newest n: a busy command's day is more than n rows.
            if (asked.figures.peak) put("at_once", PerfPeak(matching).json())
            putJsonArray("rows") { rows.forEach { add(rowJson(it, gaps, asked.tags, reading)) } }
            longestSilence(filter, matching)?.let { (row, silence) ->
                put("longest_silence_ms", silence)
                put("longest", rowJson(projection.complete(listOf(row)).single(), gaps, asked.tags, reading))
            }
            // Each further question the request carried reads the window already in hand: the cost of a read is the
            // file's, not the question's, so ten questions are one read (Settings asks about a hundred counts).
            if (asked.asks.isNotEmpty()) {
                putJsonArray("asks") { asked.asks.forEach { add(turnsJson(head, projection, it, names, gaps)) } }
            }
        }
    }

    /** The [rows] of [matching] the clamp keeps, oldest first for the newest and dearest first for the price. */
    private fun kept(matching: List<PerfRow>, rows: AskedRows, gaps: TurnPriceGap): List<PerfRow> = when (rows.order) {
        TurnsOrder.NEWEST -> matching.sortedBy { it.ts }.takeLast(rows.n)
        // Dearest first over the whole window; a request with no figure sorts after every priced one.
        TurnsOrder.PRICE -> dearestFirst(matching, gaps).take(rows.n)
    }

    // why: each row is priced ONCE, then sorted by that figure. A comparator that priced both rows on every comparison
    // asked the rate card about n log n times; a week of rows took 90 s on desk c (jstack, Oct 12).
    private fun dearestFirst(matching: List<PerfRow>, gaps: TurnPriceGap): List<PerfRow> =
        matching.map { it to (gaps.value(it) ?: Double.NEGATIVE_INFINITY) }
            .sortedWith(compareByDescending<Pair<PerfRow, Double>> { it.second }.thenByDescending { it.first.ts })
            .map { it.first }

    /** For `test=waited`: the matching request that sat through the longest silence, over every matching row. */
    private fun longestSilence(filter: TurnsFilter, matching: List<PerfRow>): Pair<PerfRow, Long>? {
        val floor = filter.waits.silenceMs
        return if (filter.waits.test != TurnsTest.WAITED_OUT || floor == null) {
            null
        } else {
            matching.map { it to TurnsTest.WAITED_OUT.silence(it, floor) }.maxByOrNull { it.second }
        }
    }

    /** One row as the console renders it: the numeric bag spread under its own keys, then the named
     *  facts written OVER it, so a key the writer put in both is reported by the typed fact and never
     *  by the bag. `ts` is the only such key today and is skipped from the bag to avoid writing it
     *  twice; the ordering is what makes that a safety net rather than a coincidence. */
    private fun rowJson(
        row: PerfRow,
        gaps: TurnPriceGap,
        tags: ProjectTags,
        reading: AccountReading,
    ): JsonObject = buildJsonObject {
        row.fields.forEach { (key, value) -> if (key != TS) put(key, value) }
        put(TS, row.ts)
        put("outcome", row.outcome)
        put("cause", row.cause)
        // ABSENT STAYS ABSENT. A null here is a row that never carried the field — `cache_cold` is
        // written only alongside an account, so a row without one never had the question asked — and
        // the JSON null is the third state that keeps the console from rendering "the cache was warm"
        // over a turn where nothing looked. A missing `model` or `compact` is a legacy or torn row and
        // is reported the same way, rather than filled in with a value the file does not contain.
        put("model", row.facts.model)
        put("session", row.facts.session)
        // The project object the session rows carry; null where the session is unknown or outside every project.
        put("project", row.facts.session?.let(tags::of) ?: JsonNull)
        // A Compare models send: no session, no project, and never "no project"; the console groups it by this field.
        put("compare", row.fields[PerfKeys.COMPARE] == 1L)
        // What a session with no name is called, from the same snapshot; null where it has a name or none is readable.
        put("title", row.facts.session?.let(tags::titleOf))
        put("account", reading.key(row.facts.account))
        put("account_label", reading.label(row.facts.account))
        put("cache_cold", row.facts.cacheCold)
        put("compact", row.facts.compact)
        // V4-444: whether prompts and answers were saved for this turn, and what the upstream itself said when the
        // upstream is what failed. Both pass through as the writer set them, null where the row carries neither.
        put("capture", row.facts.captured)
        put("provider_message", row.facts.providerMessage)
        // V4-345: the trace turn the console opens this row's request by; null where none was kept.
        put("turn", row.turn)
        put("turn_id", row.turnId)
        put("session_id", row.transcript.sessionId)
        put("response_message_id", row.transcript.responseMessageId)
        // The daemon uses the head's card, as the exact trace read does (TraceRoute.turnJson), and the
        // same classification the window's counters are folded by says why a row has no figure: null
        // cost_reason exactly where cost_usd is a number, so the console never has to guess which it is.
        put("cost_usd", gaps.usd(row))
        put("cost_reason", gaps.of(row)?.wire)
        // A plan covered this request: what it would have cost at the API rates, kept apart from any cost.
        put("plan_value_usd", gaps.value(row).takeIf { gaps.onPlan(row) })
    }

    /** What a reply of `in_tokens` and `out_tokens` on `head` costs: the decision a recorded request gets. */
    public suspend fun price(call: ApplicationCall) {
        val query = call.request.queryParameters
        val name = query["head"].orEmpty()
        val head = heads.byName(name).firstOrNull { it.key == name }
        val tokensIn = query["in_tokens"]?.toLongOrNull()
        val tokensOut = query["out_tokens"]?.toLongOrNull()
        when {
            head == null -> refuse(call, "unknown head: $name", HttpStatusCode.BadRequest)
            tokensIn == null || tokensOut == null ->
                refuse(call, "in_tokens and out_tokens are both required numbers", HttpStatusCode.BadRequest)
            else -> {
                val reply = RepliedTokens(query["model"], query["account"], tokensIn, tokensOut)
                val priced = ReplyPrice(heads).json(head, reply, clock())
                call.respondText(priced.toString(), ContentType.Application.Json)
            }
        }
    }

    private suspend fun refuse(call: ApplicationCall, message: String, status: HttpStatusCode) {
        call.respondText(
            buildJsonObject { put("error", message) }.toString(),
            ContentType.Application.Json,
            status,
        )
    }
}
