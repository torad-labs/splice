// PORT-OF: ControlServer.kt (authJson, authAction) @ a77531a — invariants unchanged: the file's two
// independent reaches into splice.core.auth, carrying the same 2026-07-18 honesty contract (a
// failed refresh must report ok:false). The fully-qualified splice.core.auth.RefreshableAuthProvider
// becomes a normal import here, its sole home now.
package splice.control.api

import io.ktor.http.ContentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import splice.control.HeadAccountPoolView
import splice.control.ManagedHead
import splice.core.auth.AuthDescription
import splice.core.auth.RefreshableAuthProvider

internal class AuthRoutes(
    private val heads: Map<String, ManagedHead>,
    private val resolver: HeadResolver,
) {
    // PORT-OF server/src/control/api.mjs auth payload @ pre-public-port-baseline: keyed by head (Node hardcodes
    // `codex`; multi-head keys each), value = {kind, login, present, ...describe fields}. The webui
    // AuthPayload reads every configured head. login = automated for oauth, manual for api-key.
    suspend fun authJson(): String {
        val described = heads.values.map { managed ->
            Triple(managed, managed.auth.describe(), managed.accountAuth?.descriptions().orEmpty())
        }
        return buildJsonObject {
            described.forEach { (managed, description, accountAuth) ->
                putJsonObject(managed.head.key) {
                    put("kind", description.kind)
                    put("login", if (description.kind.contains("oauth")) "automated" else "manual")
                    put("present", description.present)
                    description.fields.forEach { (key, value) -> put(key, value) }
                    managed.accountPool?.view(null)?.let { pool ->
                        AccountPoolJson().write(this, pool, accountAuth)
                    }
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

internal class AccountPoolJson {
    fun write(
        into: JsonObjectBuilder,
        view: HeadAccountPoolView,
        descriptions: Map<String, AuthDescription> = emptyMap(),
    ) {
        into.putJsonObject("account_pool") {
            put("selected_label", view.selectedLabel)
            putJsonArray("accounts") {
                view.accounts.forEach { account ->
                    addJsonObject {
                        put("label", account.label)
                        put("primary", account.primary)
                        put("selected", account.selected)
                        put("available", account.available)
                        put("credential_present", account.credentialPresent)
                        put("auth_excluded_until_epoch_millis", account.authExcludedUntilEpochMillis)
                        put("auth_exclusion_reason", account.authExclusionReason)
                        put("plan", account.plan)
                        put("five_hour_used_percent", account.fiveHourUsedPercent)
                        put("five_hour_reset_epoch_seconds", account.fiveHourResetEpochSeconds)
                        put("seven_day_used_percent", account.sevenDayUsedPercent)
                        put("seven_day_reset_epoch_seconds", account.sevenDayResetEpochSeconds)
                        descriptions[account.label]?.let { description -> auth(this, description) }
                    }
                }
            }
            view.lastSwitch?.let { switch ->
                putJsonObject("last_switch") {
                    put("from", switch.from)
                    put("to", switch.to)
                    put("reason", switch.reason)
                    put("at_epoch_millis", switch.atEpochMillis)
                }
            }
        }
    }

    private fun auth(into: JsonObjectBuilder, description: AuthDescription) {
        into.putJsonObject("auth") {
            put("kind", description.kind)
            put("present", description.present)
            description.fields.forEach { (key, value) ->
                if (safeAuthField(key)) put(key, value)
            }
        }
    }

    private fun safeAuthField(key: String): Boolean = when (key) {
        "account_id_masked", "login", "refresh_latched" -> true
        else -> false
    }
}
