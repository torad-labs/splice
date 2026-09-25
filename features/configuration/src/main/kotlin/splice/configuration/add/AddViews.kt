// NEW: V4-220 item 3 (2026-09-25) — the console add's wire shapes. Every /api/add/{id} answer is the
// SAME session view, read at the moment it is written (the credential re-checked, the sign-in polled),
// so the console holds one type and never infers a state the daemon did not report.
package splice.configuration.add

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import splice.accounts.signin.LoginStatus
import splice.core.topology.AuthKind
import splice.core.topology.AuthKindRegistry

private const val SIGN_IN_LOGIN = "login"
private const val SIGN_IN_KEY = "key"
private const val SIGN_IN_NONE = "none"

internal class AddViews {

    /** One catalogue profile, with what the operator must still supply ([asks]). */
    fun profile(p: AddProfile): JsonObject = buildJsonObject {
        put("name", p.name)
        put("summary", p.summary)
        put("auth_kind", p.authKind)
        put("base_url", p.baseUrl)
        put("head_key", p.headKey)
        put("command", p.command)
        putJsonArray("models") { p.models.forEach { add(model(it)) } }
        putJsonArray("asks") {
            if (p.headKey.isEmpty()) add("name")
            if (p.baseUrl == null) add("base_url")
            if (p.models.isEmpty()) add("models")
        }
    }

    /** The session as it stands now. */
    fun session(console: AddConsole, s: AddSession): JsonObject = buildJsonObject {
        val c = s.candidate
        val kind = c.provider.auth.kind
        put("id", s.id)
        put("profile", s.profile)
        put("key", c.key)
        put("command", c.command)
        put("auth_kind", kind)
        put("base_url", c.provider.baseUrl)
        putJsonArray("models") { c.resolved.models.forEach { add(model(it)) } }
        put("sign_in_by", signInBy(kind))
        put("key_env", if (signInBy(kind) == SIGN_IN_KEY) console.keyEnv(s) else null)
        val credential = console.credential(s)
        putJsonObject("credential") {
            put("present", credential.ok)
            put("detail", credential.detail)
        }
        put("sign_in", console.signInStatus(s)?.let(::login) ?: JsonNull)
        put("checks", s.checks?.let(::checks) ?: JsonNull)
        put("saved", s.saved?.let { saved(c, it) } ?: JsonNull)
    }

    fun checks(rows: List<AddCheck>): JsonArray = buildJsonArray {
        rows.forEach { row ->
            addJsonObject {
                put("name", row.name)
                put("ok", row.ok)
                put("detail", row.detail)
            }
        }
    }

    /** A failed check as the one sentence a refusal carries. */
    fun failure(rows: List<AddCheck>): String =
        rows.firstOrNull { !it.ok }?.let { "The ${it.name} check failed: ${it.detail}." } ?: "A check failed."

    private fun saved(c: AddCandidate, saved: AddSaved): JsonObject = buildJsonObject {
        put("path", c.path.toString())
        putJsonObject("wrapper") {
            when (val link = saved.link) {
                AddLinked.Linked -> put("linked", true)
                is AddLinked.NotLinked -> {
                    put("linked", false)
                    val why = link.why?.let { " ($it)" }.orEmpty()
                    put("error", "The ${c.command} command was not linked$why; run splice install ${c.key}.")
                }
            }
        }
        put("restart", restart(saved.restart))
    }

    /** The restart a save took, as its wire status: the view's `restart.status` and the save's log line. */
    fun restartStatus(taken: AddRestartTaken): String = when (taken) {
        AddRestartTaken.Draining -> "draining"
        is AddRestartTaken.Refused -> "refused"
        is AddRestartTaken.Waiting -> "waiting"
    }

    private fun restart(taken: AddRestartTaken): JsonObject = buildJsonObject {
        put("status", restartStatus(taken))
        when (taken) {
            AddRestartTaken.Draining -> Unit
            is AddRestartTaken.Refused -> put("error", taken.reason)
            is AddRestartTaken.Waiting -> {
                putJsonArray("compactions") {
                    taken.compactions.forEach { slot ->
                        addJsonObject {
                            put("head", slot.head)
                            put("age_ms", slot.ageMs)
                        }
                    }
                }
            }
        }
    }

    private fun login(status: LoginStatus): JsonObject = buildJsonObject {
        put("id", status.id)
        put("state", status.state.wire)
        put("user_code", status.userCode)
        put("verification_uri", status.verificationUri)
        put("browser_url", status.browserUrl)
        put("failure_reason", status.failureReason)
    }

    private fun model(m: AddModel): JsonObject = buildJsonObject {
        put("id", m.id)
        put("label", m.label)
        put("context_window", m.contextWindow)
        putJsonArray("slots") { m.slots.forEach { add(it) } }
    }

    private fun signInBy(kind: String): String = when {
        kind == AuthKind.Client.wire -> SIGN_IN_NONE
        AuthKindRegistry.isOAuth(kind) -> SIGN_IN_LOGIN
        else -> SIGN_IN_KEY
    }
}
