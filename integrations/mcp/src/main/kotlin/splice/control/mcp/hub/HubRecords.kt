
package splice.control.mcp.hub

import java.nio.file.Path

// why: a holder socket and record use the first KEY_HEX hex digits of its key, short enough for a Unix socket path
internal const val KEY_HEX = 16
internal const val HOLDER_STARTING = "starting"
internal const val HOLDER_LIVE = "live"
internal const val HOLDER_EXITED = "exited"

/** The holder record concerns stay separate: files, exact ownership, and generation exit evidence. */
internal class HubRecords(dir: Path) {
    val files = HubRecordFiles(dir)
    val ownership = HubRecordOwnership(files)
    val legacy = HubLegacyRecords()
    val exits = HubHolderExitEvidence(dir, files, ownership)
}
