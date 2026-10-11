// NEW: Oct 10, 2026 — the wire under Settings > Your data's history row: what splice holds, what a
// shorter window would delete, and the save that deletes exactly that.
//
// THREE CALLS, AND THE RULE THAT TIES THEM TOGETHER.
//  · GET /api/history — the saved window, what is held (turns, bytes, the oldest moment), the days
//    the bar is drawn from, and the rate the row reads as "About N MB a month".
//  · GET /api/history?days=N — the same, with `window` describing the window the person is NOW
//    CHOOSING (the saved one is still there as `saved`), plus `cut`: what picking it would delete.
//  · PUT /api/history {days, delete_before_epoch_ms} — saves N and deletes exactly the records
//    before the moment that was on the screen.
//
// THE MOMENT IS THE CONTRACT. `delete_before_epoch_ms` is not advice, it is the promise the person
// said yes to: the number under "Delete N turns" was counted at that moment, so the deletion is made
// at that moment and not at whatever the window works out to by the time the save arrives. A moment
// NEWER than the window now warrants is refused, because it would delete more than was shown; an
// older one is honoured and simply deletes less, which the background sweep tidies at its own pace.
// Every moment this wire hands out, and every moment it accepts, is on the hour
// (HistoryWindow.onTheHour): the hourly rollup can only be cut there, and a cut finer than that
// leaves a bucket whose records are half gone. It is also wide enough that the seconds a person
// spends reading the confirmation cannot move it. The tally counts at the minute, so an hour
// boundary is exact for it: no record is ever half counted.
//
// A PARTIAL READING NEVER AUTHORIZES A DELETION. If any record file could not be read whole, the
// count is not the whole count, so a save that carries a moment is refused outright with the reason
// and nothing is saved and nothing is deleted. The row reads "Not saved", which is true.
//
// SHORTENED TWICE BEFORE THE FIRST SWEEP SETTLES (hitstop): nothing is remembered between calls.
// The prune finishes on the file lane before the PUT answers, and the next read rescans every file
// whose size or modified time moved, so the second cut is counted from what the first one left.
package splice.head.perf

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import splice.core.config.StatePaths
import splice.core.perf.HistoryWindow
import splice.core.perf.HistoryWindowWords
import splice.core.util.Cancellables
import splice.core.util.DaemonLog
import splice.core.util.JsonWire
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import splice.core.util.WallClock
import splice.http.JsonReply
import java.time.ZoneId
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

// why: a week of writing is enough to say what a month of it costs in disk, and short enough to
// answer for how the person works now rather than how they worked a month ago.
private val RATE_WINDOW_MS = 168.hours.inWholeMilliseconds

// why: the rate is quoted a month at a time, and a month is 30 days for this purpose.
private val RATE_MONTH_MS = 30.days.inWholeMilliseconds

private const val BYTES = "bytes"

private val NO_STORES = HeldStores(emptyList(), null)

// why: one sentence for a reading that is not whole, said by the figure that is partial and by the
// refusal that will not delete on it, so a person reads the same cause in both places.
private const val NOT_WHOLE = "the records did not read whole: "

// why: the one refusal for a window that is neither a count of days nor the word forever, naming
// the setting and quoting what it got. The console's picker and its Custom field can only send whole
// days or `forever`, so this is read by someone editing splice.toml or the env var in a terminal,
// and the person fixing the file needs to see what they wrote (fin, Oct 10, 2026).
private const val NOT_A_WINDOW = "historyRetentionDays takes a number of days or forever, not"

// why: the same refusal TurnKeptRoutes gives for the same cause, so one unwired install reads the
// same way on both rows of Your data.
private const val NOT_WIRED = "turn statistics paths are not wired"

/** Where a chosen window is persisted. Returns null when it was saved, or why it was refused. */
public fun interface HistoryWindowStore {
    public fun save(text: String): String?
}

/**
 * Everything else the one window covers, trimmed to the same moment the records were cut at: the
 * hourly spending totals, and finished sessions' own totals.
 *
 * Marlin, Oct 10, 2026: when a person says yes to "Delete 25,877 turns", everything history covers
 * is gone at that moment. Someone who chose "Today only" for privacy and still saw last week's
 * spending on Usage would have been told something false, and no sentence on the page covers that.
 * Returns the first reason a store could not be trimmed, so a save that did not do all of what it
 * said says so, or null when it did.
 */
public fun interface HistoryStores {
    public fun trimBefore(momentMs: Long): String?

    /** What these stores hold from before [momentMs], measured when asked and never from a figure kept. Stores
     *  that are not counted say nothing, which is what a caller that only trims gets. */
    public fun heldBefore(momentMs: Long): HeldStores = HeldStores(emptyList(), null)

    /** Every compaction summary held, whatever its age: a two-hour parking spot has no days to draw, so the page
     *  shows the total apart from the band. A save clears the ones the cut reaches, with the other stores. */
    public fun summariesHeld(): HeldStores = HeldStores(emptyList(), null)
}

/** One store a cut reaches, and what it holds before the cut. [key] is a name the page owns the words for. */
public data class HeldStore(val key: String, val bytes: Long, val files: Int? = null)

/** What the stores a cut reaches hold before it: reasoning kept between turns, code mode work, and the rest of what
 *  Your data lists but the records do not count. Only stores holding something are listed, and a store that was
 *  measured and holds nothing is absent, never a zero entry. [unreadable] says why a store could not be counted,
 *  so a total is never taken from a count that was not whole. */
public data class HeldStores(val held: List<HeldStore>, val unreadable: String?)

/** The history row's reads and its one save. */
public class HistoryRoutes(
    private val paths: StatePaths?,
    private val saved: HistoryWindowStore,
    /** The other stores the window covers. Trimmed at the same moment, in the same save. */
    private val others: HistoryStores = HistoryStores { null },
    private val clock: WallClock = WallClock { System.currentTimeMillis() },
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val log: LogSink = LogSink(DaemonLog::write),
    /** Held by the caller across calls, because its scan cache is the whole reason a page poll is
     *  cheap; these routes themselves are built per request, like every other console port. */
    private val inventory: HistoryDays = HistoryDays(zone),
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** What splice holds under [window], and what [proposedDays] would delete when it is given. */
    public fun read(window: HistoryWindow, proposedDays: String? = null): JsonReply {
        val source = paths ?: return refuse(HttpStatusCode.ServiceUnavailable, NOT_WIRED)
        val proposed = proposedDays?.let { HistoryWindowWords.of(it, zone) }
        if (proposedDays != null && proposed == null) return notAWindow(proposedDays)
        return Cancellables.runCatchingCancellable {
            val held = inventory.held(source.stateDir, source.perfArchiveDir)
            val cut = proposed?.let { pick -> cutoff(pick)?.let(held::before) }
            // Measured as the read is drawn: a figure kept from an earlier read would offer a deletion of a size
            // the files no longer have.
            body(window, proposed, held, cut?.let { Cutting(it, others.heldBefore(it.cutoffMs)) })
        }.fold(
            onSuccess = { JsonReply(HttpStatusCode.OK, it) },
            onFailure = {
                refuse(HttpStatusCode.InternalServerError, "cannot read the history: ${SafeFailureText.render(it)}")
            },
        )
    }

    /** Save the window in [request] and delete exactly the records it showed the person. */
    public fun save(request: String): JsonReply {
        val source = paths ?: return refuse(HttpStatusCode.ServiceUnavailable, NOT_WIRED)
        val asked = asked(request)
        if (asked.window == null) return notAWindow(asked.word)
        val window = asked.window
        return Cancellables.runCatchingCancellable { apply(window, asked.deleteBefore, source) }.fold(
            onSuccess = { it },
            onFailure = {
                refuse(HttpStatusCode.InternalServerError, "cannot save the window: ${SafeFailureText.render(it)}")
            },
        )
    }

    private fun apply(window: HistoryWindow, asked: Long?, source: StatePaths): JsonReply {
        val held = inventory.held(source.stateDir, source.perfArchiveDir)
        // On the hour, whatever arrived. The console echoes back the moment a read handed it, which
        // is already on the hour, so this binds only a hand-written PUT: a cut inside an hour would
        // take part of an hourly bucket's records and leave the bucket (HistoryWindow.onTheHour).
        val moment = asked?.let(window::onTheHour)
        // The save is attempted only once the moment is allowed, so a refusal leaves both the window
        // and the records exactly as they were and the row reads "Not saved" truthfully.
        val refused = refusal(window, held, moment)
            ?: saved.save(window.text)?.let { refuse(HttpStatusCode.BadRequest, "not saved: $it") }
        if (refused != null) return refused
        // The stores are counted BEFORE they are trimmed, so the answer says what this save took and not what is left.
        val stores = moment?.let { others.heldBefore(it) }
        val taken = moment?.let { HistoryPrune(log).before(it, source.stateDir, source.perfArchiveDir) }
        // The records and everything else history covers are cut at ONE moment, the one the person
        // was shown. A store that could not be trimmed is named in the answer rather than passed
        // over: the records are already gone, so silence here would read as "all of it went".
        val missed = moment?.let { others.trimBefore(it) }
        val after = inventory.held(source.stateDir, source.perfArchiveDir)
        val cut = taken?.let { Cutting(HistoryCut(checkNotNull(moment), it.turns, it.bytes), stores ?: NO_STORES) }
        return JsonReply(
            HttpStatusCode.OK,
            body(window, proposed = null, held = after, cutting = cut, missed = missed),
        )
    }

    /** Why this save cannot delete what it was asked to, or null when it can. */
    private fun refusal(window: HistoryWindow, held: HistoryHeld, moment: Long?): JsonReply? {
        val allowed = cutoff(window)
        return when {
            moment == null -> null
            held.readError != null -> {
                // The row reads "Not saved" and the cause goes to the log, where every source
                // sentence goes (fin, Oct 10, 2026). The console re-asks the read and redraws the
                // band, because the count on the button was the wrong count.
                log("[history] a save was refused: " + NOT_WHOLE + held.readError + "\n")
                refuse(HttpStatusCode.ServiceUnavailable, NOT_WHOLE + held.readError)
            }
            allowed == null || moment > allowed ->
                refuse(HttpStatusCode.Conflict, "that is not where this window cuts; read the history again")
            else -> null
        }
    }

    /** The window and moment a save carries, or null when the body does not name a window. */

    /** What a save body asked for. [Asked.window] is null when the body named no window splice
     *  understands, and [Asked.word] is then what it did name, for the refusal to quote. */
    private fun asked(request: String): Asked {
        val read = Cancellables.runCatchingCancellable { json.parseToJsonElement(request).jsonObject }
            .onFailure { log("[history] a save body that is not JSON was refused: ${SafeFailureText.render(it)}\n") }
        val document = read.getOrNull() ?: return Asked(null, null, "")
        val word = (document["days"] as? JsonPrimitive)?.content.orEmpty()
        return Asked(
            HistoryWindowWords.of(word, zone),
            (document["delete_before_epoch_ms"] as? JsonPrimitive)?.longOrNull,
            word,
        )
    }

    private fun body(
        window: HistoryWindow,
        proposed: HistoryWindow?,
        held: HistoryHeld,
        cutting: Cutting?,
        missed: String? = null,
    ): String =
        JsonWire.string(
            buildJsonObject {
                put("window", spelled(proposed ?: window))
                if (proposed != null) put("saved", spelled(window))
                putJsonObject("held") {
                    put("turns", held.turns)
                    put(BYTES, held.bytes)
                    put("oldest_epoch_ms", held.oldestMs)
                    put("unknown_turns", held.unknownTurns)
                }
                putJsonArray("days") { held.days.forEach { day -> add(spelled(day)) } }
                put("rate_bytes_per_month", rate(held))
                cutting?.let { put("cut", spelled(it.cut, it.stores)) }
                summaries(this)
                // Said where every figure above it is: a reading that is not whole cannot be read as
                // a total, and it is the same sentence that refuses a deletion counted on it.
                held.readError?.let { put("reason", NOT_WHOLE + it) }
                missed?.let { put("not_deleted", it) }
            },
        )

    /** The compaction summaries held, which belong to no day and so sit beside the band, not in it. */
    private fun summaries(into: JsonObjectBuilder) {
        val held = others.summariesHeld()
        held.held.singleOrNull()?.let { into.putJsonObject("compaction_summaries") { put(BYTES, it.bytes) } }
        held.unreadable?.let { into.put("compaction_summaries_reason", it) }
    }

    private fun spelled(window: HistoryWindow): JsonObject = buildJsonObject {
        put("text", window.text)
        put("days", window.days)
        put("cutoff_epoch_ms", cutoff(window))
        put("forever", window.forever)
        put("nothing", window.nothing)
    }

    private fun spelled(day: HistoryDay): JsonObject = buildJsonObject {
        put("start_epoch_ms", day.startMs)
        put("turns", day.turns)
        put(BYTES, day.bytes)
    }

    private fun spelled(cut: HistoryCut, stores: HeldStores): JsonObject = buildJsonObject {
        put("cutoff_epoch_ms", cut.cutoffMs)
        put("turns", cut.turns)
        put(BYTES, cut.bytes)
        // No entry is written for a store that holds nothing before the cut.
        putJsonArray("stores") {
            for (store in stores.held.filter { it.bytes > 0 }) {
                add(
                    buildJsonObject {
                        put("key", store.key)
                        put(BYTES, store.bytes)
                        store.files?.let { put("files", it) }
                    },
                )
            }
        }
        stores.unreadable?.let { put("stores_reason", it) }
    }

    /** Where this window cuts. On the hour, because the window says so and every reader of it gets
     *  the same answer: the count this hands out and the cut a save makes cannot disagree. */
    private fun cutoff(window: HistoryWindow): Long? = window.cutoffMs(clock())

    /** What a month of writing at the last week's pace occupies. */
    private fun rate(held: HistoryHeld): Long =
        held.bytesSince(clock() - RATE_WINDOW_MS) * RATE_MONTH_MS / RATE_WINDOW_MS

    /** The refusal for a window that is neither days nor forever, quoting what arrived. */
    private fun notAWindow(value: String?): JsonReply =
        refuse(HttpStatusCode.BadRequest, "$NOT_A_WINDOW \"${value.orEmpty()}\"")

    private fun refuse(status: HttpStatusCode, why: String): JsonReply =
        JsonReply(status, JsonWire.string(buildJsonObject { put("error", why) }))

    /** A cut and what the other stores held from before it, which are one fact and travel as one. */
    private data class Cutting(val cut: HistoryCut, val stores: HeldStores)

    private data class Asked(val window: HistoryWindow?, val deleteBefore: Long?, val word: String)
}
