// PORT-OF: ControlServer.kt (authJson, authAction) @ a77531a — invariants unchanged: the file's two
// independent reaches into splice.core.auth, carrying the same 2026-07-18 honesty contract (a
// failed refresh must report ok:false). The fully-qualified splice.core.auth.RefreshableAuthProvider
// becomes a normal import here, its sole home now.
package splice.control.api

import io.ktor.http.ContentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import splice.control.ManagedHead
import splice.core.auth.RefreshableAuthProvider

internal class AuthRoutes(
    private val heads: Map<String, ManagedHead>,
    private val resolver: HeadResolver,
) {
    // PORT-OF server/src/control/api.mjs auth payload @ pre-public-port-baseline: keyed by head (Node hardcodes
    // `codex`; multi-head keys each), value = {kind, login, present, ...describe fields}. The webui
    // AuthPayload reads every configured head. login = automated for oauth, manual for api-key.
    suspend fun authJson(): String {
        val described = heads.values.map { m -> m to m.auth.describe() }
        return buildJsonObject {
            described.forEach { (m, desc) ->
                putJsonObject(m.head.key) {
                    put("kind", desc.kind)
                    put("login", if (desc.kind.contains("oauth")) "automated" else "manual")
                    put("present", desc.present)
                    desc.fields.forEach { (k, v) -> put(k, v) }
                }
            }
        }.toString()
    }

    suspend fun authAction(call: ApplicationCall) {
        val key = call.parameters["head"].orEmpty()
        val action = call.parameters["action"].orEmpty()
        val managed = resolver.resolveHeadOrRespond(call, key) ?: return
        val refreshable = managed.auth as? RefreshableAuthProvider
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
