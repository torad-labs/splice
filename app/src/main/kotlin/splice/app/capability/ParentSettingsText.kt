// NEW: what splice says of Claude Code's `parentSettingsBehavior`, one wording for the doctor's row (CapabilityTools)
// and the action item `splice upgrade` and `splice setup --update` print (CapabilityGaps). The fact is about the
// managed settings file, not about its processWrapper: a file that exists and does not say "merge" makes Claude Code
// drop the policy Claude Desktop hands each Code session, whatever else it holds. Only the file splice read is spoken
// of by name; the other places Claude Code reads are said to be unread, and that they may set the key.
package splice.app.capability

import splice.client.PARENT_SETTINGS_KEY
import splice.client.PARENT_SETTINGS_MERGE

/** What [managed], the managed settings file, says of `parentSettingsBehavior`, and what could be done about it. */
internal class ParentSettingsText(private val managed: ManagedFileFacts) {
    /** The state of the key in the file, with the places splice did not read. */
    fun said(): String {
        val parent = managed.parent
        val file = managed.file
        val state = when {
            parent.merges ->
                "$file sets $PARENT_SETTINGS_KEY to \"$PARENT_SETTINGS_MERGE\", so Claude Desktop's limits apply to " +
                    "the Code sessions it starts"
            parent.absent -> "$file does not set $PARENT_SETTINGS_KEY, $PARENT_DROPS_POLICY"
            else -> "$file sets $PARENT_SETTINGS_KEY to ${parent.shown}, $PARENT_DROPS_POLICY"
        }
        return state + unread()
    }

    /** What to do, null where the file already says "merge". Setting a value other than "merge" is the
     *  administrator's choice, so it is not put as a fix. */
    fun fix(): String? = when {
        managed.parent.merges -> null
        managed.parent.absent ->
            "add \"$PARENT_SETTINGS_KEY\": \"$PARENT_SETTINGS_MERGE\" to it (`splice desktop wrapper` shows what to " +
                "put where)"
        else ->
            "that is the administrator's choice: setting it to \"$PARENT_SETTINGS_MERGE\" puts Claude Desktop's " +
                "limits under the administrator's own for the Code sessions Desktop starts, and stops Desktop's warning"
    }

    private fun unread(): String =
        if (managed.elsewhere.isEmpty()) {
            ""
        } else {
            "; splice did not read ${managed.elsewhere.joinToString(", ")}, which Claude Code also reads and where a " +
                "setting may win over the file's"
        }
}

private const val PARENT_DROPS_POLICY = "so Claude Code drops the policy Claude Desktop hands each Code session it " +
    "starts, and Desktop warns in every one"
