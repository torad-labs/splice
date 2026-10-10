// NEW: Oct 10, 2026 — which terminal carries which session, in `session-panes.json` under the state dir
// (SessionPanes, console TMUX.md). The console's half of "splice drives only what it opened".
//
// WHY IT IS ON DISK. A pane outlives the daemon. splice restarts on every install, and a session the person
// is sitting in front of keeps running — so a record only in memory would make every restart take the
// console's ability to drive the sessions it had started, while they carried on in their terminals. The file
// is the one thing that survives, so it is what is asked.
//
// WHY IT IS OPAQUE. A pane is an opaque [SessionPane] id all the way down to this file: the store never
// reads inside it and could not say which terminal drew it. That is what makes this store survive tmux's
// replacement — the day seatd arrives, a record written by tmux reads as a pane seatd does not know, the
// terminal refuses it, and nothing here needs changing.
//
// WHY A GONE PANE IS NOT DELETED ON READ. Whether a pane is still live is the TERMINAL's question, and
// asking it costs a process per session. [paneFor] answers what splice remembers; the caller that acts on
// the pane learns it is gone from the act, which is the same answer one turn later and a hundred times
// cheaper on a list of thirty sessions. [forget] is for a session that ENDED, which is a fact the caller
// has and this store cannot discover.
//
// THE FILE. One JSON object of session id to pane id, written whole through SecureFile's temp-then-atomic
// move at 0600 — a pane id names a socket path in the person's own run directory, which is theirs and not
// the world's. Reads are served from memory and re-read when the file's modification time moves, so a hand
// edit is seen without a restart. A file that does not parse is never silently replaced by an empty
// document: it is read as empty for this process, and the next write refuses, because overwriting it would
// throw away every session the console could still have driven.
package splice.sessions.registry

import kotlinx.serialization.json.Json
import splice.core.session.SessionPane
import splice.core.session.SessionPanes
import splice.core.util.Cancellables
import splice.core.util.PathProbe
import splice.core.util.SecureFile
import java.nio.file.Files
import java.nio.file.Path

// why: the state-dir file the panes live in. Internal: the name is this store's business, and a caller that
// spelled it would be a second reader of the same fact.
private const val SESSION_PANES_FILE = "session-panes.json"

/**
 * The record of which pane carries which session, kept across restarts.
 *
 * [stateDir] is the daemon's state directory. The daemon is the only writer.
 */
public class RememberedPanes(stateDir: Path) : SessionPanes {
    private val file: Path = stateDir.resolve(SESSION_PANES_FILE)
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()

    private var panes: MutableMap<String, String> = mutableMapOf()
    private var readAt: Long? = null
    private var parsed = true

    override fun paneFor(sessionId: String): SessionPane? = synchronized(lock) {
        refresh()
        panes[sessionId]?.let(::SessionPane)
    }

    override fun remember(sessionId: String, pane: SessionPane): Unit = synchronized(lock) {
        refresh()
        if (panes[sessionId] == pane.id) return
        panes[sessionId] = pane.id
        write()
    }

    override fun forget(sessionId: String): Unit = synchronized(lock) {
        refresh()
        if (panes.remove(sessionId) == null) return
        write()
    }

    /** Re-reads the file when it has moved since the last read, so a hand edit needs no restart. */
    private fun refresh() {
        val modified = PathProbe.modified(file)?.toMillis()
        if (modified == readAt && readAt != null) return
        readAt = modified
        if (modified == null) {
            panes = mutableMapOf()
            parsed = true
            return
        }
        val read = Cancellables.runCatchingCancellable {
            json.decodeFromString<Map<String, String>>(Files.readString(file))
        }
        parsed = read.isSuccess
        panes = read.getOrNull()?.toMutableMap() ?: mutableMapOf()
    }

    /** A document that did not parse is never overwritten: every session it still names could be driven. */
    private fun write() {
        require(parsed) { "$SESSION_PANES_FILE does not parse, so the panes it holds are not replaced" }
        SecureFile.writeAtomic0600(file, json.encodeToString(panes.toMap()))
        readAt = PathProbe.modified(file)?.toMillis()
    }
}
