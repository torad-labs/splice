// NEW: account edit capabilities and credential-proved display identity share one roster presentation boundary.
package splice.accounts.pool

import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import splice.accounts.claude.ClaudeAccountIdentity

// why: the label the edit routes name a command's first OAuth account by (OAuthAccountFiles reserves it).
private const val PRIMARY_ID = "primary"

/** A first OAuth account the console may edit: its [name], when a person gave it one, and whether it is [removable]. */
internal data class FirstAccountEdit(val name: String?, val removable: Boolean)

/** What a row lets a person do to its account, and the [id] the edit routes name it by (null: no edit target). */
internal data class RowEdit(val canRename: Boolean, val canRemove: Boolean, val id: String?)

internal class AccountRowManagement {
    /** An added account can be renamed and removed. A first OAuth account can be renamed, and removed when its file is
     *  splice's own. Any other row keeps the edit target it had and offers neither. */
    fun edit(label: String?, primary: Boolean, first: FirstAccountEdit?): RowEdit = when {
        label != null && !primary -> RowEdit(canRename = true, canRemove = true, id = label)
        first != null -> RowEdit(canRename = true, canRemove = first.removable, id = label ?: PRIMARY_ID)
        else -> RowEdit(canRename = false, canRemove = false, id = label)
    }

    fun write(into: JsonObjectBuilder, displayName: String, identity: ClaudeAccountIdentity?, edit: RowEdit) {
        into.put("display_name", displayName)
        into.put("identity_verified", identity != null)
        into.put("can_remove", edit.canRemove)
        into.put("can_rename", edit.canRename)
        if (edit.id != null) {
            into.putJsonObject("edit_target") {
                put("kind", "pool")
                put("id", edit.id)
            }
        } else {
            into.put("edit_target", null as String?)
        }
        identity?.let { account ->
            into.putJsonObject("account") {
                put("uuid", account.uuid)
                put("email", account.email)
            }
        }
    }
}
