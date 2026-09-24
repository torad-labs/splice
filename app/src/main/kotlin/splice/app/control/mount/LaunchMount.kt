// NEW: LAYOUT-01 — the launch capability's routes: the /launch exec recipe, the SessionStart resume hook,
// and the Claude head's wrap state (features/launch).
package splice.app.control.mount

import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import splice.app.control.LaunchHeadAdapter
import splice.app.control.ManagedHead
import splice.app.control.api.ControlAudit
import splice.app.control.api.HeadResolver
import splice.core.util.LogSink
import splice.http.JsonBody
import splice.launch.recipe.LaunchRoutes
import splice.launch.recipe.LaunchService
import splice.launch.resume.ResumeHookRoute
import splice.launch.wrap.ClaudeHeadRoutes

internal class LaunchMount(
    heads: Map<String, ManagedHead>,
    resolver: HeadResolver,
    launchService: LaunchService?,
    audit: ControlAudit,
    log: LogSink,
    private val guard: ControlGuard,
) {
    private val launchHeads = LaunchHeadAdapter.heads(heads, resolver)
    private val launchRoutes = LaunchRoutes(launchHeads, launchService, LaunchHeadAdapter.audit(audit), JsonBody())

    // V4-129 review: the wrap routes act on the SAME wrap the launch route resolves a wrapped
    // `claude` through (LaunchService.wrap), so what /api/claude-head/wrap writes is what /launch
    // reads. A server built without a LaunchService launches nothing, and keeps the default wrap.
    private val claudeHeadRoutes =
        launchService?.let { ClaudeHeadRoutes(launchHeads, wrappedHead = it.wrap) } ?: ClaudeHeadRoutes(launchHeads)

    // V4-169: the SessionStart resume hook's receiving end — session-guarded like the statusline, and
    // reachable only from loopback, because the daemon binds there.
    private val resumeHookRoute = ResumeHookRoute(launchHeads, log)

    fun register(route: Route) {
        route.post("/launch/{head}") { guard.guarded(call) { launchRoutes.launch(call) } }
        route.post("/hooks/resume/{head}") { guard.guarded(call, Door.SESSION) { resumeHookRoute.resume(call) } }
        route.get("/api/claude-head") { guard.guarded(call) { claudeHeadRoutes.status(call) } }
        route.post("/api/claude-head/wrap") { guard.guarded(call) { claudeHeadRoutes.wrap(call) } }
        route.post("/api/claude-head/unwrap") { guard.guarded(call) { claudeHeadRoutes.unwrap(call) } }
    }
}
