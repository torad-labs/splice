// NEW: Oct 27, 2026 — whether a running Claude Code session holds a feedback draft the person has not sent. Claude Code
// 2.1.310 drafts feedback during a session's turns and keeps each draft in <its config folder>/feedback/drafts/
// <draft_id>.json, naming the session it came from (source_session_id), with "queued" in its status until it is sent.
// Such a session answers /exit with "You have 1 unsent feedback draft · Enter to review & send · Esc to discard and
// exit" instead of exiting, and sending or discarding the draft is the person's call. On Oct 27 at 08:30 CT a relaunch
// typed /exit into one in ~/.claude-claudex and left its client sitting at that question.
//
// The file is the record: the status line's "· 1 feedback draft" only shows it. A head's seats share its config
// folder, so a draft counts only for the session it names, and a file that cannot be read or parsed as one, such as a
// draft Claude Code left cut off mid-way, counts for none.
package splice.sessions.registry

import kotlinx.serialization.json.Json
import splice.core.util.JsonScalars
import splice.core.util.PathProbe
import java.nio.file.Path

// why: the status Claude Code 2.1.310 gives a feedback draft until it is sent
private const val UNSENT_DRAFT = "queued"

/** The config folder a running session's client runs in, as its process names it; null when it cannot be read. */
public fun interface ConfigFolderOf {
    public operator fun invoke(record: SessionRecord): Path?
}

/** The feedback drafts each running session keeps in the config folder [folderOf] names for it. */
public class FeedbackDrafts(private val folderOf: ConfigFolderOf) {
    /** True when [record]'s config folder holds a draft from [record]'s session that is still "queued". A folder that
     *  cannot be read, or a process whose folder cannot be, shows none. */
    public fun unsent(record: SessionRecord): Boolean {
        val session = record.sessionId ?: return false
        val drafts = folderOf(record)?.resolve("feedback")?.resolve("drafts") ?: return false
        return PathProbe.entries(drafts).any { file ->
            val draft = PathProbe.text(file)?.let { JsonScalars.objectOrNull(Json, it) }
            JsonScalars.str(draft, "source_session_id") == session && JsonScalars.str(draft, "status") == UNSENT_DRAFT
        }
    }
}
