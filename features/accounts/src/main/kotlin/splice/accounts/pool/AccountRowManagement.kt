// NEW: account edit capabilities and credential-proved display identity share one roster presentation boundary.
package splice.accounts.pool

import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import splice.accounts.claude.ClaudeAccountIdentity

internal class AccountRowManagement {
    fun write(
        into: JsonObjectBuilder,
        label: String?,
        primary: Boolean,
        displayName: String?,
        identity: ClaudeAccountIdentity?,
    ) {
        into.put("display_name", displayName ?: label ?: "Primary")
        into.put("identity_verified", identity != null)
        into.put("can_remove", label != null && !primary)
        into.put("can_rename", label != null && !primary)
        if (label != null) {
            into.putJsonObject("edit_target") {
                put("kind", "pool")
                put("id", label)
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
