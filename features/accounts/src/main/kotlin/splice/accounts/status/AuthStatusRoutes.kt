// PORT-OF: ControlServer.kt (authJson, authAction) @ a77531a, by way of daemon/control/.../api/auth/AuthRoutes.kt
// — invariants unchanged: the file's two independent reaches into splice.core.auth, carrying the same
// 2026-07-18 honesty contract (a failed refresh must report ok:false). The sign-in, switch and edit
// routes that shared AuthRoutes are their own slices now (signin/, pool/, edit/).
package splice.accounts.status

import io.ktor.http.ContentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import splice.accounts.AccountHead
import splice.accounts.AccountHeadResolver
import splice.accounts.CredentialVerdictJson
import splice.accounts.pool.AccountPoolJson
import splice.core.auth.RefreshableAuthProvider

private const val LOGIN_FILE_UNREADABLE = "can't read the login file"

public class AuthStatusRoutes(
    private val heads: Map<String, AccountHead>,
    private val resolver: AccountHeadResolver,
) {
    /** The only provider fields the answer carries. A provider's description also holds the credential file's path,
     *  the key file's path and a read failure whose text can quote one: those stay on the machine's disk and out of
     *  the answer, and a field a provider adds later stays out until it is named here. The key appears only masked.
     *  A read failure keeps its place with fixed words in place of the provider's text, so a login that cannot be
     *  read still says so without quoting where. */
    private val shown = setOf("display_name", "refusal", "env_var", "api_key_masked")

    // Keyed by head, value = {present, verdict, ...the shown describe fields}: what Compare, Teams, Accounts and the
    // doctor read, and no more. V4-220 item 6b: verdict = {state, at_epoch_ms?}; state is `held` for a credential splice holds,
    // and for a forwarded one (auth.kind = client) what upstream last said: unverified, accepted, rejected.
    public suspend fun authJson(): String {
        val described = heads.values.map { head ->
            Triple(head, head.auth.describe(), head.accountAuth?.descriptions().orEmpty())
        }
        return buildJsonObject {
            described.forEach { (head, description, accountAuth) ->
                putJsonObject(head.key) {
                    put("present", description.present)
                    CredentialVerdictJson().write(this, description.verdict)
                    description.fields.filterKeys { it in shown }.forEach { (key, value) -> put(key, value) }
                    if ("read_error" in description.fields) put("read_error", LOGIN_FILE_UNREADABLE)
                    head.activePool?.view(null)?.let { pool ->
                        AccountPoolJson().write(this, pool, accountAuth)
                    }
                }
            }
        }.toString()
    }

    public suspend fun authAction(call: ApplicationCall) {
        val key = call.parameters["head"].orEmpty()
        val action = call.parameters["action"].orEmpty()
        val head = resolver.resolveOrRespond(call, key) ?: return
        val refreshable = head.auth as? RefreshableAuthProvider
        if (action == "refresh" && refreshable != null) {
            // The dashboard's primary remediation control must not lie: a failed refresh
            // (null credentials back) reports ok:false so the operator re-logins instead of
            // staring at a green button while 401s continue (audit 2026-07-18).
            val refreshed = refreshable.refresh()
            call.respondText(
                buildJsonObject {
                    put("ok", refreshed != null)
                    put("head", key)
                    if (refreshed == null) put("note", "refresh failed; sign in again (splice logs has the cause)")
                }.toString(),
                ContentType.Application.Json,
            )
        } else {
            // browser login lands with the launcher (P4-LAUNCH); ack for now
            call.respondText(
                buildJsonObject {
                    put("ok", false)
                    put("note", "not supported in-process")
                }.toString(),
                ContentType.Application.Json,
            )
        }
    }
}
