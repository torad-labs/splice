// NEW: Nov 12, 2026: a reader that holds the rows of the sessions that are gone is not sent them again.
//
// A machine keeps the registration of every session it has seen. On the machine this was measured on that day the
// listing held 3,259 sessions, 3,227 of them gone: every answer to GET /api/sessions was 2.3 MB, 25 KiB of it the 32
// sessions still here, and an open Sessions page asks every five seconds. A gone session's row does not change from
// one read to the next (two reads seven seconds apart: 3,227 rows, the same bytes both times), so the answer names
// the gone rows by a mark of their bytes, and a reader that sends back the mark it holds gets them again only once
// the mark differs.
//
// Nov 19, 2026: the listing sends the newest GONE_ROWS_SENT of the gone alone, and says how many older ones it left
// for GET /api/sessions/history. A reader that sent no mark was still sent every gone row, so the answer grew with
// every session ever opened: 3.3 MB and 4,441 gone rows that night, read fresh by every process that asks, and slow
// enough under load to miss the console's five-second limit.
package splice.sessions.listing

import kotlinx.serialization.json.JsonObject
import java.security.MessageDigest
import java.util.HexFormat

/** Builds the row of one session the listing sends. A row reads the session's transcript and repository, so building
 *  every gone session's row to send a hundred of them made the first read after a restart take 25 s (Nov 19, 2026). */
internal fun interface ListedRowBuild {
    operator fun invoke(): JsonObject
}

/** One session of a listing: whether it is gone, when its registration was last written, and its row, built only
 *  when the listing sends it. */
internal class ListedRow(val gone: Boolean, val updatedAt: Long, build: ListedRowBuild) {
    val json: JsonObject by lazy(LazyThreadSafetyMode.NONE) { build() }
}

/** One listing's rows in the registry's order: the sessions still here, and the newest [GONE_ROWS_SENT] of the gone,
 *  named by a mark of their bytes. */
internal class GoneSessionRows(rows: List<ListedRow>) {
    /** The places in the listing of the gone rows sent. */
    private val newestGone: Set<Int> = rows.indices.filter { rows[it].gone }
        .sortedByDescending { rows[it].updatedAt }.take(GONE_ROWS_SENT).toSet()

    private val sent: List<ListedRow> = rows.filterIndexed { place, row -> !row.gone || place in newestGone }

    /** How many gone sessions are older than the ones sent: GET /api/sessions/history has them. */
    val older: Int = rows.count(ListedRow::gone) - newestGone.size

    /** The mark of the gone rows sent: a byte of one changed, a row added or a row removed gives another. */
    val mark: String = HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").also { digest ->
            sent.filter(ListedRow::gone).forEach { digest.update("${it.json}\n".encodeToByteArray()) }
        }.digest(),
    )

    /** Whether a reader that sent [held] already has every gone row this listing sends. */
    fun heldBy(held: String?): Boolean = held == mark

    /** The rows a reader that sent [held] is owed: every row sent, or the rows of the sessions still here alone. */
    fun owed(held: String?): List<JsonObject> = sent.filter { !heldBy(held) || !it.gone }.map(ListedRow::json)
}

/** The gone sessions a listing sends, newest first by when their registration was last written. */
internal const val GONE_ROWS_SENT = 100
