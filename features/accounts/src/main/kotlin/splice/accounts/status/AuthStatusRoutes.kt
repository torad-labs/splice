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
import splice.accounts.pool.AccountPoolJson
import splice.core.auth.RefreshableAuthProvider

public class AuthStatusRoutes(
    private val heads: Map<String, AccountHead>,
    private val resolver: AccountHeadResolver,
) {
    // PORT-OF server/src/control/api.mjs auth payload @ pre-public-port-baseline: keyed by head (Node hardcodes
    // `codex`; multi-head keys each), value = {kind, login, present, ...describe fields}. The webui
    // AuthPayload reads every configured head. login = automated for oauth, manual for api-key.
    public suspend fun authJson(): String {
        val described = heads.values.map { head ->
            Triple(head, head.auth.describe(), head.accountAuth?.descriptions().orEmpty())
        }
        return buildJsonObject {
            described.forEach { (head, description, accountAuth) ->
                putJsonObject(head.key) {
                    put("kind", description.kind)
                    put("login", if (description.kind.contains("oauth")) "automated" else "manual")
                    put("present", description.present)
                    description.fields.forEach { (key, value) -> put(key, value) }
                    head.pool?.view(null)?.let { pool ->
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
                    if (refreshed == null) put("note", "refresh failed — run: splice logs; re-login likely required")
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
