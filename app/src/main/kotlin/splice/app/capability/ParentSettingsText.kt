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
 *  could be done about it. */
internal class ParentSettingsText(private val managed: ManagedFileFacts) {
    /** The state of the key in the file, with the places splice did not read. */
    fun said(): String {
        val parent = managed.parent
        val file = managed.file
        val state = when {
            managed.state == ManagedFileState.MISSING -> "there is no $file"
            managed.state == ManagedFileState.UNREADABLE -> unreadable(PARENT_SETTINGS_KEY)
            parent.merges ->
                "$file sets $PARENT_SETTINGS_KEY to \"$PARENT_SETTINGS_MERGE\", so Claude Desktop's limits apply to " +
                    "the Code sessions it starts"
            parent.absent -> "$file does not set $PARENT_SETTINGS_KEY, $PARENT_DROPS_POLICY"
            else -> "$file sets $PARENT_SETTINGS_KEY to ${parent.shown}, $PARENT_DROPS_POLICY"
        }
        return state + unread()
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
            "add \"$PARENT_SETTINGS_KEY\": \"$PARENT_SETTINGS_MERGE\" to it (`splice desktop wrapper` shows what to " +
                "put where)"
        else ->
            "that is the administrator's choice: setting it to \"$PARENT_SETTINGS_MERGE\" puts Claude Desktop's " +
                "limits under the administrator's own for the Code sessions Desktop starts, and stops Desktop's warning"
    }

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

private const val PARENT_DROPS_POLICY = "so Claude Code drops the policy Claude Desktop hands each Code session it " +
    "starts, and Desktop warns in every one"
