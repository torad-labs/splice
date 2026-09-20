// NEW: V4-129 — GET /api/claude-head, POST /api/claude-head/{wrap,unwrap} (FEATURES.md §6, 4.12).
// The wrap/unwrap MECHANICS (shim swap, the narrow ~/.claude materialization, backup/restore) live
// in splice.core.launch.WrappedHead; this file is the HTTP surface plus the one piece only :control
// can supply — the splice-owned Claude head's (claude-splice) own LaunchSpec, which is what wrap
// materializes into the vanilla dir instead of the isolated one. "no pool, no isolation, no un-link
// on a wrapped head" (the row title): this route touches no ManagedHead state beyond reading that
// one head's already-assembled spec.
package splice.control.api

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import splice.control.LaunchSpec
import splice.control.ManagedHead
import splice.core.launch.ClaudeHeadStatus
import splice.core.launch.ClaudeLogins
import splice.core.launch.MaterializeSpec
import splice.core.launch.UnwrapResult
import splice.core.launch.WrapResult
import splice.core.launch.WrappedHead
import java.nio.file.Path
import java.nio.file.Paths

/** The one head wrap targets (config/splice.example.toml:682-692): the splice-owned Claude head
 *  whose command is deliberately NOT `claude` (its own comment says why), so wrap borrows its
 *  already-assembled catalog/statusline/login wiring and retargets the write at the vanilla dir. */
private const val CLAUDE_HEAD_KEY = "claude-splice"

internal class ClaudeHeadRoutes(
    private val heads: Map<String, ManagedHead>,
    home: Path = Paths.get(System.getProperty("user.home")),
    private val wrappedHead: WrappedHead = WrappedHead(home),
    private val claudeLogins: ClaudeLogins = ClaudeLogins(),
) {
    suspend fun status(call: ApplicationCall) {
        call.respondText(statusJson(wrappedHead.status()), ContentType.Application.Json)
    }

    suspend fun wrap(call: ApplicationCall) {
        val source = heads[CLAUDE_HEAD_KEY]?.launchSpec
        if (source == null) {
            respondUnconfigured(call)
            return
        }
        when (val result = wrappedHead.wrap(materializeSpecFrom(source))) {
            is WrapResult.Ok -> call.respondText(
                buildJsonObject {
                    put("ok", true)
                    putStatus(this, result.status)
                    put("settings_backup_path", result.settingsBackupPath)
                    put("claude_json_backup_path", result.claudeJsonBackupPath)
                }.toString(),
                ContentType.Application.Json,
            )
            is WrapResult.Refused -> respondRefused(call, result.reason)
        }
    }

    suspend fun unwrap(call: ApplicationCall) {
        when (val result = wrappedHead.unwrap()) {
            is UnwrapResult.Ok -> call.respondText(
                buildJsonObject {
                    put("ok", true)
                    putStatus(this, result.status)
                }.toString(),
                ContentType.Application.Json,
            )
            is UnwrapResult.Refused -> respondRefused(call, result.reason)
        }
    }

    private suspend fun respondUnconfigured(call: ApplicationCall) {
        call.respondText(
            errorJson("the '$CLAUDE_HEAD_KEY' head is not configured — wrap needs its catalog to materialize"),
            ContentType.Application.Json,
            HttpStatusCode.ServiceUnavailable,
        )
    }

    private suspend fun respondRefused(call: ApplicationCall, reason: String) {
        call.respondText(errorJson(reason), ContentType.Application.Json, HttpStatusCode.Conflict)
    }

    /** [source]'s already-assembled fields carry over untouched (catalog, statusline, login wiring);
     *  configDir and policy are placeholders — [WrappedHead.wrap] overrides both to the vanilla dir
     *  and a policy that always carries its settings forward, regardless of claude-splice's own
     *  share/isolate configuration. advertiseKeySetup is forced off: a client-auth head never needs
     *  the paste-a-key capture hook. */
    private fun materializeSpecFrom(source: LaunchSpec): MaterializeSpec = MaterializeSpec(
        configDir = source.trees.own,
        policy = source.policy,
        availableModelIds = source.availableModelIds,
        defaultModel = source.pinnedModel,
        modelOptionsCache = source.modelOptionsCache,
        statuslineCommand = source.statuslineCommand,
        loginCommand = source.loginCommand,
        signInLabel = source.signInLabel,
        signInViaBrowser = source.signInViaBrowser,
        tokenCapture = source.tokenCapture,
        loginOutcomeFile = source.loginOutcomeFile,
        headKey = source.headKey,
        advertiseKeySetup = false,
    )

    private fun statusJson(status: ClaudeHeadStatus): String = buildJsonObject {
        putStatus(this, status)
        val labels = claudeLogins.labels()
        putJsonObject("claude_logins") {
            put("count", labels.size)
            put("selected", claudeLogins.selected())
            putJsonArray("labels") { labels.forEach { add(it) } }
            // FEATURES.md 4.5: "the constraint ... is printed on the strip" — carried here rather
            // than on /api/auth, which ClientAuthProvider (generic to every client-auth head, not
            // Claude-specific) is the wrong layer for a Claude-only sentence; see FINAL REPORT.
            put(
                "constraint",
                "one login per Claude head at a time, chosen at session launch; no mid-session switch",
            )
        }
    }.toString()

    private fun putStatus(into: JsonObjectBuilder, status: ClaudeHeadStatus) {
        into.put("mode", status.mode)
        into.put("resolves_to", status.resolvesTo)
        into.put("shim_path", status.shimPath)
        into.put("real_binary_path", status.realBinaryPath)
    }

    private fun errorJson(message: String): String = buildJsonObject { put("error", message) }.toString()
}
