// NEW: V4-129 — GET /api/claude-head, POST /api/claude-head/{wrap,unwrap} (FEATURES.md §6, 4.12).
// The wrap/unwrap MECHANICS (the shim swap and its state file) live in splice.client.wrap.WrappedHead; this
// file is the HTTP surface plus the one check only the daemon can make — that the splice-owned Claude head
// (claude-splice) is configured, since a wrapped `claude` launches exactly that head (V4-445: the launch
// carries its settings; wrap itself writes nothing into ~/.claude). "no pool, no isolation, no un-link on a
// wrapped head" (the row title): this route reads no head state beyond whether that one head exists, through
// the LaunchHeads port (LAYOUT-01).
package splice.launch.wrap

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
import splice.client.ClaudeLogins
import splice.client.wrap.ClaudeHeadStatus
import splice.client.wrap.UnwrapResult
import splice.client.wrap.WrapResult
import splice.client.wrap.WrappedHead
import splice.core.config.UserHome
import splice.launch.LaunchHeads
import splice.launch.LaunchReplies
import java.nio.file.Path

/** The one head wrap targets (app/src/main/resources/splice.example.toml): the splice-owned Claude head
 *  whose command is deliberately NOT `claude` (its own comment says why), which a launch through the wrapped
 *  `claude` runs over the operator's own config.
 *  V4-129 review: internal, because a launch THROUGH the wrapped `claude` (LaunchRoutes) runs this
 *  same head, and the two must never name different ones. */
internal const val CLAUDE_HEAD_KEY = "claude-splice"

public class ClaudeHeadRoutes(
    private val heads: LaunchHeads,
    home: Path = UserHome.dir(),
    private val wrappedHead: WrappedHead = WrappedHead(home),
    private val claudeLogins: ClaudeLogins = ClaudeLogins(),
) {
    public suspend fun status(call: ApplicationCall) {
        call.respondText(statusJson(wrappedHead.status()), ContentType.Application.Json)
    }

    public suspend fun wrap(call: ApplicationCall) {
        if (heads.byKey(CLAUDE_HEAD_KEY) == null) {
            respondUnconfigured(call)
            return
        }
        when (val result = wrappedHead.wrap()) {
            is WrapResult.Ok -> call.respondText(
                buildJsonObject {
                    put("ok", true)
                    putStatus(this, result.status)
                }.toString(),
                ContentType.Application.Json,
            )
            is WrapResult.Refused -> respondRefused(call, result.reason)
        }
    }

    public suspend fun unwrap(call: ApplicationCall) {
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
            LaunchReplies.errorJson(
                "the '$CLAUDE_HEAD_KEY' head is not configured, and a wrapped claude launches it",
            ),
            ContentType.Application.Json,
            HttpStatusCode.ServiceUnavailable,
        )
    }

    private suspend fun respondRefused(call: ApplicationCall, reason: String) {
        call.respondText(LaunchReplies.errorJson(reason), ContentType.Application.Json, HttpStatusCode.Conflict)
    }

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
            // V4-276: a switch happens only through `splice login <head> --label`, never at launch.
            put(
                "constraint",
                "one login per Claude head at a time; `splice login <head> --label <name>` saves or switches " +
                    "it, only while no session of that head is running",
            )
        }
    }.toString()

    private fun putStatus(into: JsonObjectBuilder, status: ClaudeHeadStatus) {
        into.put("mode", status.mode)
        into.put("resolves_to", status.resolvesTo)
        into.put("shim_path", status.shimPath)
        into.put("real_binary_path", status.realBinaryPath)
    }
}
