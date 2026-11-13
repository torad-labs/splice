// NEW: Nov 12, 2026: a reader that holds the rows of the sessions that are gone is not sent them again.
//
// A machine keeps the registration of every session it has seen. On the machine this was measured on that day the
// listing held 3,259 sessions, 3,227 of them gone: every answer to GET /api/sessions was 2.3 MB, 25 KiB of it the 32
// sessions still here, and an open Sessions page asks every five seconds. A gone session's row does not change from
// one read to the next (two reads seven seconds apart: 3,227 rows, the same bytes both times), so the answer names
// the gone rows by a mark of their bytes, and a reader that sends back the mark it holds gets them again only once
// the mark differs. A reader that sends no mark gets every row, as before.
package splice.sessions.listing

import kotlinx.serialization.json.JsonObject
import java.security.MessageDigest
import java.util.HexFormat

/** One session's row of a listing, and whether that session is gone. */
internal data class ListedRow(val gone: Boolean, val json: JsonObject)

/** One listing's rows in the registry's order, the gone sessions' named by a mark of their bytes. */
internal class GoneSessionRows(private val rows: List<ListedRow>) {
    /** The mark of the gone sessions' rows: a byte of one changed, a row added or a row removed gives another. */
    val mark: String = HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").also { digest ->
            rows.filter(ListedRow::gone).forEach { digest.update("${it.json}\n".encodeToByteArray()) }
        }.digest(),
    )

    /** Whether a reader that sent [held] already has every gone row of this listing. */
    fun heldBy(held: String?): Boolean = held == mark

    /** The rows a reader that sent [held] is owed: every row, or the rows of the sessions still here alone. */
    fun owed(held: String?): List<JsonObject> = rows.filter { !heldBy(held) || !it.gone }.map(ListedRow::json)
}
