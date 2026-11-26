// NEW: door-default (docs/specs/door-default.md, decision 3). Claude Desktop as a caller of the door
// (docs/specs/desktop-door.md, desktop-full.md): its desktop key, its switch (`splice desktop on` and `off`), its own
// default model, the window each of its requests holds, and what the door has seen of it, kept for `splice desktop`:
// the last turn, when an app-mode request last came (which says whether Desktop has been reopened since splice changed
// its configuration) and the rows its last discovery was answered with (which say whether the picker changed since).
package splice.app.desktop

import splice.app.desktop.catalog.DesktopLineup
import splice.app.desktop.record.DesktopKey
import splice.app.desktop.record.DesktopLastTurn
import splice.app.desktop.record.DesktopPickerRead
import splice.app.desktop.record.DesktopPickerRow
import splice.app.desktop.record.DesktopRecord
import splice.app.desktop.record.DesktopRecords
import splice.app.desktop.record.RecordChange
import splice.core.model.ClientWindow
import splice.core.turn.DESKTOP_DOOR
import splice.core.util.Cancellables
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import splice.core.util.WallClockIso
import splice.share.RefusalVoice
import java.time.Instant

internal class DesktopCaller(
    private val key: DesktopKey,
    private val records: DesktopRecords,
    private val log: LogSink,
    private val now: WallClockIso = WallClockIso { Instant.now().toString() },
) : DoorCaller(RefusalVoice(DESKTOP_ERROR_PREFIX), DESKTOP_DOOR) {
    override fun presents(header: String?): Boolean = key.matchesBearer(header)

    override fun closed(): String? = OFF.takeUnless { records.get().on }

    override fun default(lineup: DesktopLineup): String? = records.get().defaultModel

    override fun requestWindowOf(ask: DoorAsk): ClientWindow? = ask.appWindow

    override val noHead: String = "no provider is in the door; `splice desktop` says why"

    override val notInDoor: String = "is not in the door; `splice desktop` lists the models it serves"

    // Desktop's picker is the door's own list (DoorCatalog), Claude's models under their own ids, so a Claude id it
    // sends is a model picked from that list: it runs where a provider serves it, as the terminal's `claude` does, and
    // Claude Code's own side calls still run the small tier.
    override val runsNamedModels: Boolean = true

    /** The last door turn, kept for `splice desktop`. */
    override fun sending(turn: DesktopLastTurn) = kept("the last door turn") { it.copy(lastTurn = turn) }

    /** An app-mode request: Desktop has been reopened since splice changed its configuration. Recorded once after each
     *  change, so a turn writes nothing while the record already says so. */
    override fun asked(ask: DoorAsk) {
        if (!ask.appMode || records.get().seenSinceWritten()) return
        kept("Claude Desktop's request") { it.copy(appSeenAt = now()) }
    }

    /** An app-mode discovery answered with [rows]: what Desktop's picker now lists. */
    override fun discovered(rows: List<DesktopPickerRow>) {
        val record = records.get()
        if (record.pickerRead?.rows == rows && record.seenSinceWritten()) return
        val at = now()
        kept("the picker Claude Desktop read") { it.copy(appSeenAt = at, pickerRead = DesktopPickerRead(at, rows)) }
    }

    /** [change] kept in the record. A record the disk refuses is said in the log, and the request goes on: what it is
     *  for is the answer, not the record of it. */
    private fun kept(what: String, change: RecordChange<DesktopRecord>) {
        Cancellables.runCatchingCancellable { records.update(change) }.onFailure { failure ->
            log("[desktop] $what was not recorded: ${SafeFailureText.render(failure)}\n")
        }
    }
}

// What a request of Desktop's is told while the door is off.
private const val OFF = "the door is off; `splice desktop on` sets Claude Desktop up on splice"
