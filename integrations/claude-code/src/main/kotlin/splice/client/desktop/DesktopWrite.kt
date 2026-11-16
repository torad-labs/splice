// NEW: desktop-door (docs/specs/desktop-door.md, decision 4). The shapes `splice desktop on` is given and gives back:
// a picker row, everything one `on` writes, and the file once it has (DesktopSettings).
package splice.client.desktop

import kotlinx.serialization.json.JsonObject

/** One row of the Code tab's picker. */
public data class DesktopRow(val model: String, val label: String, val description: String)

/** What `on` writes: the door's address, the desktop key, the declared window, the default model's selector and the
 *  picker's rows. */
public data class DesktopWrite(
    val door: String,
    val key: String,
    val window: Long,
    val model: String,
    val rows: List<DesktopRow>,
)

/** A settings file once `on` has written into it, and the record of what it replaced there. */
public data class DesktopOn(val file: JsonObject, val replaced: JsonObject)
