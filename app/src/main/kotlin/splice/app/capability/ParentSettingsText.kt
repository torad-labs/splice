// NEW: what splice says of Claude Code's managed settings, one wording for the doctor's rows (CapabilityTools) and the
// action item `splice upgrade` and `splice setup --update` print (CapabilityGaps). The facts are about the managed
// settings file, not about its processWrapper: a file that exists and does not say `parentSettingsBehavior` "merge"
// makes Claude Code drop the policy Claude Desktop hands each Code session, whatever else it holds. A file splice could
// not read is neither of "sets" and "does not set": it is said to be unread, for both keys. Only the file splice read
// is spoken of by name; the other places Claude Code reads are said to be unread, and that they may set the key.
package splice.app.capability

import splice.client.PARENT_SETTINGS_KEY
import splice.client.PARENT_SETTINGS_MERGE

/** What [managed], where Claude Code's managed settings are or may be, says of `parentSettingsBehavior`, and what
 *  could be done about it. Where another source that splice did not read exists, nothing here says what Desktop does or
 *  that a key must be added: it says what the file holds, names the source, and what is true if no source sets
 *  "merge". */
internal class ParentSettingsText(private val managed: ManagedFileFacts) {
    private val unreadSources: Boolean get() = managed.elsewhere.isNotEmpty()

    /** The state of the key in the file, with the places splice did not read. */
    fun said(): String {
        val parent = managed.parent
        val file = managed.file
        val state = when {
            managed.state == ManagedFileState.MISSING -> "there is no $file"
            managed.state == ManagedFileState.UNREADABLE -> unreadable(PARENT_SETTINGS_KEY)
            parent.merges ->
                "$file sets $PARENT_SETTINGS_KEY to \"$PARENT_SETTINGS_MERGE\"" + effect("so $LIMITS_APPLY")
            parent.absent -> "$file does not set $PARENT_SETTINGS_KEY" + effect("so $PARENT_DROPS_POLICY")
            else -> "$file sets $PARENT_SETTINGS_KEY to ${parent.quoted}" + effect("so $PARENT_DROPS_POLICY")
        }
        return state + unread() + conditional()
    }

    /** That the file could not be read, so splice cannot say what it holds for [key]. */
    fun unreadable(key: String): String =
        "${managed.file} could not be read (no permission, or it is not a JSON object), so splice cannot say what it " +
            "holds for $key"

    /** What to do, null where the file already says "merge". Setting a value other than "merge" is the
     *  administrator's choice, so it is not put as a fix. */
    fun fix(): String? = when {
        managed.state == ManagedFileState.MISSING ->
            "check that one of them sets $PARENT_SETTINGS_KEY to \"$PARENT_SETTINGS_MERGE\": without it Claude " +
                "Desktop's limits for the Code tab are not applied and Desktop warns in every Code session"
        managed.state == ManagedFileState.UNREADABLE ->
            "read it as an administrator and check that it has both keys (`splice desktop wrapper` says what they are)"
        managed.parent.merges -> null
        managed.parent.absent ->
            (if (unreadSources) "check whether one of those sets it; if none does, " else "") +
                "add \"$PARENT_SETTINGS_KEY\": \"$PARENT_SETTINGS_MERGE\" to it (`splice desktop wrapper` shows " +
                "what to put where)"
        else ->
            "that is the administrator's choice: setting it to \"$PARENT_SETTINGS_MERGE\" puts Claude Desktop's " +
                "limits under the administrator's own for the Code sessions Desktop starts, and stops Desktop's warning"
    }

    /** What the file's value does, said only where no unread source could change it. */
    private fun effect(what: String): String = if (unreadSources) "" else ", $what"

    /** What is true if no source sets "merge": said where an unread source leaves the effect open. */
    private fun conditional(): String =
        if (unreadSources && withoutMerge()) {
            "; if no source sets \"$PARENT_SETTINGS_MERGE\", $PARENT_DROPS_POLICY"
        } else {
            ""
        }

    private fun withoutMerge(): Boolean = managed.state == ManagedFileState.READ && !managed.parent.merges

    private fun unread(): String = when {
        managed.elsewhere.isEmpty() -> ""
        managed.state == ManagedFileState.MISSING ->
            "; splice did not read ${managed.elsewhere.joinToString(", ")}, which Claude Code reads and which may " +
                "set $PARENT_SETTINGS_KEY"
        else ->
            "; splice did not read ${managed.elsewhere.joinToString(", ")}, which Claude Code also reads and where a " +
                "setting may win over the file's"
    }
}

private const val LIMITS_APPLY = "Claude Desktop's limits apply to the Code sessions it starts"

private const val PARENT_DROPS_POLICY = "Claude Code drops the policy Claude Desktop hands each Code session it " +
    "starts, and Desktop warns in every one"
