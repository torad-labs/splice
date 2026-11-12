// NEW: V4-276 — the types of `splice login <claude-head> --label` (ClaudeLogins.login): what the
// verb answers, what it is told about the head's sessions, and what it reads off the head and the
// store. Split out of ClaudeLogins.kt for the concentration law; no behaviour of its own.
package splice.client

import java.nio.file.Path

public sealed class ClaudeLoginResult {
    public data object Ok : ClaudeLoginResult()

    /** [ClaudeLogins.login] went through; [said] tells the operator what changed and what comes next. */
    public data class Done(val said: String) : ClaudeLoginResult()

    /** [inUse]: refused because a session of the command is running, so its login can't change under it. */
    public data class Refused(val reason: String, val inUse: Boolean = false) : ClaudeLoginResult()
}

/** The sessions of the head whose login [ClaudeLogins.login] would change, as the caller's session
 *  registry reads them. [Unreadable] is refused exactly like a live session: never guess. */
public sealed class HeadSessions {
    /** One line per session of the head whose process still runs. */
    public data class Read(val live: List<String>) : HeadSessions()
    public data class Unreadable(val why: String) : HeadSessions()
}

/** The head [ClaudeLogins.login] acts on: its topology key, for the sentences, and its config dir. */
public data class ClaudeHead(val key: String, val configDir: Path)

/** Native default Claude keeps its account file outside its credential directory. */
public data class ClaudeLoginTarget(val head: ClaudeHead, val accountFile: Path)

/** An account as Claude Code names it in oauthAccount; the uuid decides. The email is kept in the record, never said. */
internal data class Account(val uuid: String, val email: String?)

/** A label's V4-276 record: its account, and whether its copy may be older than the account's last
 *  refresh (its login left the head without a save-back), which is never put back. */
internal data class Record(val account: Account, val stale: Boolean)

/** The head's live login: none, one whose account Claude Code has not recorded, or a known account's. */
internal sealed class Live {
    data object Absent : Live()
    data class Unreadable(val why: String) : Live()
    data class Held(val bytes: String, val account: Account) : Live()
}
