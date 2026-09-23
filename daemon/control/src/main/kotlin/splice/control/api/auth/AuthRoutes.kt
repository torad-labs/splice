// PORT-OF: ControlServer.kt (authJson, authAction) @ a77531a — invariants unchanged: the file's two
// independent reaches into splice.core.auth, carrying the same 2026-07-18 honesty contract (a
// failed refresh must report ok:false). The fully-qualified splice.core.auth.RefreshableAuthProvider
// becomes a normal import here, its sole home now.
package splice.control.api.auth

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import splice.control.AccountMutation
import splice.control.ConsolePorts
import splice.control.HeadAccountPinSource
import splice.control.HeadAccountPoolView
import splice.control.HeadRestart
import splice.control.LoginStart
import splice.control.LoginStatus
import splice.control.ManagedHead
import splice.control.api.HeadResolver
import splice.core.auth.AuthDescription
import splice.core.auth.RefreshableAuthProvider
import splice.http.JsonBody

// V4-132: the two body/JSON field names every login/switch/accounts route below shares.
private const val LABEL_FIELD = "label"
private const val ACCOUNTS_PORT = "accounts"

internal class AuthRoutes(
    private val heads: Map<String, ManagedHead>,
    private val resolver: HeadResolver,
    /** V4-132: read at CALL time (never captured) — ConsoleWiring assigns [ConsolePorts.accounts]
     *  after this route object is constructed, the same discipline every other console port keeps. */
    private val ports: ConsolePorts,
) {
    private val jsonBody = JsonBody()

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

    /** POST /api/auth/{head}/login (FEATURES.md §6): starts a device or browser login off-request
     *  and answers immediately with its STARTING view; the console polls [pollLogin] for the code,
     *  the link and the terminal state. Body: `{"label": "..."}`, optional. */
    suspend fun startLogin(call: ApplicationCall) {
        val key = call.parameters["head"].orEmpty()
        val managed = resolver.resolveHeadOrRespond(call, key) ?: return
        val accounts = ports.accounts ?: return respondUnwired(call, ACCOUNTS_PORT)
        val label = stringField(jsonBody.parse(call), LABEL_FIELD)
        val restart = HeadRestart { managed.head.restart() }
        when (val started = accounts.startLogin(managed.head.key, label, restart)) {
            is LoginStart.Started -> respond(call, loginStatusJson(started.status))
            LoginStart.UnknownHead -> respondError(call, "unknown head", HttpStatusCode.NotFound)
            is LoginStart.UnsupportedAuthKind -> respondError(
                call,
                "no browser or device login for auth kind '${started.kind}'",
                HttpStatusCode.BadRequest,
            )
        }
    }

    /** GET /api/auth/{head}/login/{id} (FEATURES.md §6): polls one login started by [startLogin]. */
    suspend fun pollLogin(call: ApplicationCall) {
        val key = call.parameters["head"].orEmpty()
        resolver.resolveHeadOrRespond(call, key) ?: return
        val accounts = ports.accounts ?: return respondUnwired(call, ACCOUNTS_PORT)
        val status = accounts.pollLogin(call.parameters["id"].orEmpty())
        if (status == null) {
            respondError(call, "unknown login id", HttpStatusCode.NotFound)
            return
        }
        respond(call, loginStatusJson(status))
    }

    /** POST /api/auth/{head}/switch (FEATURES.md §4.5 "Manual switch"): a REAL pin in
     *  [splice.upstream.credentials.AccountPool] — [select] tries it FIRST, ahead of the primary preference, from
     *  the next turn. Body: `{"label": "..."}`, required. A head with no pool (one login, or an
     *  unpooled kind) answers 400 naming it, never a silent no-op. */
    suspend fun switchAccount(call: ApplicationCall) {
        val key = call.parameters["head"].orEmpty()
        val managed = resolver.resolveHeadOrRespond(call, key) ?: return
        val pin = managed.accountPool as? HeadAccountPinSource
        if (pin == null) {
            respondError(call, "head '$key' has no account pool to switch", HttpStatusCode.BadRequest)
            return
        }
        val label = stringField(jsonBody.parse(call), LABEL_FIELD)
        if (label.isNullOrBlank()) {
            respondError(call, "body must name a 'label'", HttpStatusCode.BadRequest)
            return
        }
        val pinned = pin.pin(label)
        respond(
            call,
            buildJsonObject {
                put("ok", pinned)
                if (!pinned) put("error", "unknown account label '$label'")
            }.toString(),
            status = if (pinned) HttpStatusCode.OK else HttpStatusCode.BadRequest,
        )
    }

    /** DELETE /api/auth/{head}/accounts/{label} (FEATURES.md §6): removes a pooled account. */
    suspend fun removeAccount(call: ApplicationCall) {
        val key = call.parameters["head"].orEmpty()
        val managed = resolver.resolveHeadOrRespond(call, key) ?: return
        val accounts = ports.accounts ?: return respondUnwired(call, ACCOUNTS_PORT)
        val label = call.parameters[LABEL_FIELD].orEmpty()
        respondMutation(call, accounts.removeAccount(managed.head.key, label))
    }

    /** PATCH /api/auth/{head}/accounts/{label} (FEATURES.md §6): relabels a pooled account. Body:
     *  `{"label": "<new label>"}`, required — the NEW label; the path segment names the old one. */
    suspend fun relabelAccount(call: ApplicationCall) {
        val key = call.parameters["head"].orEmpty()
        val managed = resolver.resolveHeadOrRespond(call, key) ?: return
        val accounts = ports.accounts ?: return respondUnwired(call, ACCOUNTS_PORT)
        val label = call.parameters[LABEL_FIELD].orEmpty()
        val newLabel = stringField(jsonBody.parse(call), LABEL_FIELD)
        if (newLabel.isNullOrBlank()) {
            respondError(call, "body must name a new 'label'", HttpStatusCode.BadRequest)
            return
        }
        respondMutation(call, accounts.relabelAccount(managed.head.key, label, newLabel))
    }

    private suspend fun respondMutation(call: ApplicationCall, result: AccountMutation) {
        when (result) {
            AccountMutation.Ok -> respond(call, buildJsonObject { put("ok", true) }.toString())
            AccountMutation.UnknownHead -> respondError(call, "unknown head", HttpStatusCode.NotFound)
            is AccountMutation.Refused -> respondError(call, result.reason, HttpStatusCode.BadRequest)
        }
    }

    private fun loginStatusJson(status: LoginStatus): String = buildJsonObject {
        put("id", status.id)
        put("head", status.head)
        put("state", status.state.wire)
        put("user_code", status.userCode)
        put("verification_uri", status.verificationUri)
        put("browser_url", status.browserUrl)
        put("failure_reason", status.failureReason)
    }.toString()

    private suspend fun respond(call: ApplicationCall, body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        call.respondText(body, ContentType.Application.Json, status)

    private suspend fun respondError(call: ApplicationCall, message: String, status: HttpStatusCode) =
        respond(call, buildJsonObject { put("error", message) }.toString(), status)

    // NULL MEANS UNWIRED, same discipline as ConsolePorts' other nine: a named 5xx, never a
    // payload that reads as "no accounts" (FEATURES.md §6, "did-not-run" law).
    private suspend fun respondUnwired(call: ApplicationCall, what: String) = respondError(
        call,
        "the daemon wired no $what port; this route cannot answer",
        HttpStatusCode.ServiceUnavailable,
    )

    // A member, never a top-level fun (the wall bans those) or a JsonObject extension
    // (kt-no-extension-functions): JsonNull is a JsonPrimitive whose content is the literal
    // "null" (the same trap LoginIo.errorCode already steps around).
    private fun stringField(obj: JsonObject?, key: String): String? =
        (obj?.get(key) as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content
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
