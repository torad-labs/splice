// PORT-OF: ControlServer.kt (statusline) @ a77531a — invariants unchanged: the wire-plumbing half of a
// concern whose rendering half (StatuslineRenderer.kt) was already extracted. The bounded body read is
// StatuslineBodyRead.kt's (LAYOUT-01).
package splice.usage.statusline

import io.ktor.http.ContentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import splice.core.config.ConfigService
import splice.core.util.Cancellables
import splice.core.util.JsonScalars
import splice.core.version.ClientVersionTracker
import splice.usage.UsageHead
import splice.usage.UsageHeadLookup
import splice.usage.perf.HeadPerfSkipSource
import splice.usage.perf.HeadSessionPerfSource

public class StatuslineRoute(
    private val heads: UsageHeadLookup,
    private val config: ConfigService,
    private val clientVersions: ClientVersionTracker = ClientVersionTracker(),
) {
    private val renderers = RendererCache()
    private val json = Json { ignoreUnknownKeys = true }

    public suspend fun statusline(call: ApplicationCall) {
        val key = call.parameters["head"].orEmpty()
        val managed = heads.byName(key).singleOrNull()
        if (managed == null) {
            call.respondText(key, ContentType.Text.Plain)
            return
        }
        val stdin = StatuslineBodyRead.readOrRespond(call) ?: return
        // getConfig(KEY), not the global view: everything else here is this head's (label, usage,
        // warn thresholds) and statuslineGitRoots is per-head overridable, so the unkeyed read
        // silently ignored [heads.<key>.overrides].statuslineGitRoots. Found by
        // kt-head-scoped-config-must-be-keyed on its first tree scan (2026-07-26). `key` is
        // non-empty here — the managed == null early return above guarantees it resolved.
        val roots = config.getConfig(key).statuslineGitRoots
        val renderer = renderers.get(managed.key, managed.label, roots) {
            StatuslineRenderer(
                managed.label,
                roots,
                catalog = managed.catalog,
                clientWindows = managed.clientWindows,
                accountPool = managed.accountPool,
                sessionCost = sessionCostOf(managed),
                // V4-45: the same checked-cast bridge sessionCostOf uses below, and captured the
                // same way — the SOURCE, never a count, so the cached renderer reads it live.
                perfSkips = managed.perf as? HeadPerfSkipSource,
            )
        }
        val sessionId = sessionId(stdin)
        val line = renderer.render(stdin, managed.usage, managed.warnPct, managed.warnTokens5h, sessionId)
        val warning = clientVersions.statuslineWarning(sessionId)
        call.respondText(warning?.let { "$line · $it" } ?: line, ContentType.Text.Plain)
    }

    /** V4-37: the per-session cost, when this head can price one at all.
     *
     *  `perf` is typed [splice.usage.perf.HeadPerfSource] and the session-aware reader is its SIBLING
     *  interface, so this bridge is a checked cast. A head whose perf source cannot answer per
     *  session — every test double, and any future sink that keeps no session column — renders the
     *  client's own number, exactly as today. The head-level rate override is null here because the
     *  TOML field that populates it is stage two (HeadConfig, V4-36's file). */
    private fun sessionCostOf(managed: UsageHead): SessionCostSource? =
        (managed.perf as? HeadSessionPerfSource)?.let { perf -> SessionCost(perf, managed.catalog) }

    // A statusline payload splice did not author and cannot answer to: a missing session id is the
    // absence of an OPTIONAL field, not a failure, and the render path has no sink — it degrades to
    // the no-session view.
    // ast-grep-ignore: kt-no-silent-result-collapse -- a missing optional session id is absence, not a failure
    private fun sessionId(stdin: String): String? = Cancellables.runCatchingCancellable {
        JsonScalars.str(json.parseToJsonElement(stdin).jsonObject, "session_id")
    }.getOrNull()
}
