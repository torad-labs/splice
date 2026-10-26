// NEW: LAYOUT-01 — the daemon's own lifecycle on the control plane: the drain-and-stop, the restart the
// host unit completes, and the upgrade status (features/lifecycle). V4-220: the restart waits for the
// compactions the heads' gates hold (RestartAfterCompactions), read in-process off each head's health.
// V4-220 item 4: and `splice upgrade` from the console (UpgradeRunRoutes), run out of process.
package splice.app.control.mount

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import splice.app.control.ConsolePorts
import splice.app.control.ManagedHead
import splice.core.util.LogSink
import splice.head.HeadDeps
import splice.lifecycle.restart.CompactionsInFlight
import splice.lifecycle.restart.DaemonRestarts
import splice.lifecycle.restart.DaemonRoutes
import splice.lifecycle.restart.RestartAfterCompactions
import splice.lifecycle.restart.ShutdownDaemon
import splice.lifecycle.upgrade.CompactionSlot
import splice.lifecycle.upgrade.UpgradeCheckRoutes
import splice.lifecycle.upgrade.UpgradeChecksSource
import splice.lifecycle.upgrade.UpgradeRoute
import splice.lifecycle.upgrade.UpgradeRunRoutes
import splice.lifecycle.upgrade.UpgradeRunsSource
import splice.upstream.LifecycleScope
import splice.upstream.codemode.ProcessDispatchers

/** [ports] is read at CALL time: ControlPlane assigns [LifecyclePorts.supervised] and
 *  [LifecyclePorts.upgrade] after the server is constructed. */
internal class LifecycleMount(
    private val shutdownDaemon: ShutdownDaemon,
    private val ports: ConsolePorts,
    private val guard: ControlGuard,
    private val heads: Map<String, ManagedHead>,
    log: LogSink,
) {
    private val compactions = object : CompactionsInFlight {
        override fun invoke(): List<CompactionSlot> = heads.flatMap { (key, managed) ->
            managed.head.healthSnapshot().gate.live.filter { it.compact }.map { CompactionSlot(key, it.ageMs) }
        }

        override fun hold(forMs: Long): String? {
            heads.values.forEach { it.head.compactionHold?.hold(forMs) }
            return null
        }
    }
    private val restart = RestartAfterCompactions(compactions, LifecycleScope(ProcessDispatchers().background()), log)

    /** The daemon's ONE restart decision and wait; AddMount's save takes its restart through it too. */
    internal val restarts = DaemonRestarts(restart)

    // the drain cap the commands themselves stop with (HeadServerFactory builds every head on HeadPolicy's default)
    private val daemonRoutes = DaemonRoutes(restarts, HeadDeps.HeadPolicy().stopDrainMs)
    private val upgradeRoute = UpgradeRoute()
    private val upgradeRuns = UpgradeRunRoutes(UpgradeRunsSource { ports.lifecycle.upgradeRuns }, log)
    private val upgradeChecks =
        UpgradeCheckRoutes(UpgradeChecksSource { ports.lifecycle.upgradeChecks }, ProcessDispatchers().io())

    fun register(route: Route) {
        route.post("/api/daemon/shutdown") {
            guard.guarded(call) {
                // An empty body: the launcher and `splice stop` read only the 202.
                call.respondText("{}", ContentType.Application.Json, HttpStatusCode.Accepted)
                shutdownDaemon()
            }
        }
        // The same drain as shutdown: the unit or the armed detached successor brings it back.
        // A failed successor arm refuses before this daemon drains.
        route.post("/api/daemon/restart") {
            guard.guarded(call) { daemonRoutes.restartJson(call, shutdownDaemon, ports.lifecycle.supervised) }
        }
        route.get("/api/daemon/restart") { guard.guarded(call) { daemonRoutes.statusJson(call) } }
        // A restart run from a shell (`splice restart`, `splice upgrade`) holds new compactions before it reads
        // the ones in flight (CompactionWait); the console's restart holds them in-process through [compactions].
        // The hold lapses by itself, so it is bounded here: a typo cannot hold compactions for a day.
        route.post("/api/daemon/compactions/hold") {
            guard.guarded(call) {
                val ms = call.request.queryParameters["ms"]?.toLongOrNull()?.takeIf { it in 1..MAX_COMPACTION_HOLD_MS }
                if (ms == null) {
                    call.respondText(
                        """{"error":"ms must be a hold in milliseconds, at most $MAX_COMPACTION_HOLD_MS"}""",
                        ContentType.Application.Json,
                        HttpStatusCode.BadRequest,
                    )
                } else {
                    val _ = compactions.hold(ms)
                    call.respondText("""{"held_ms":$ms}""", ContentType.Application.Json, HttpStatusCode.OK)
                }
            }
        }
        // Key rotation (Oct 26, 2026): `splice rotate-key`. The answer is paths and words, never a key.
        route.post("/api/mgmt-key/rotate") { guard.guarded(call) { rotateKey(call) } }
        route.get("/api/upgrade") { guard.guarded(call) { upgradeRoute.upgradeJson(call, ports.lifecycle.upgrade) } }
        route.post("/api/upgrade") { guard.guarded(call) { upgradeRuns.start(call) } }
        route.get("/api/upgrade/run") { guard.guarded(call) { upgradeRuns.status(call) } }
        route.post("/api/upgrade/check") { guard.guarded(call) { upgradeChecks.check(call) } }
    }

    private suspend fun rotateKey(call: ApplicationCall) {
        val rotation = ports.lifecycle.keyRotation
        if (rotation == null) {
            call.respondText(
                """{"error":"this daemon cannot replace its management key"}""",
                ContentType.Application.Json,
                HttpStatusCode.ServiceUnavailable,
            )
            return
        }
        call.respondText(rotation.rotate().toString(), ContentType.Application.Json, HttpStatusCode.OK)
    }
}

// why: the longest hold a restart asks for is the whole-turn cap plus the stop after it; an hour bounds any knob
// setting a person could reasonably give that cap while refusing a hold no restart would ask for.
private const val MAX_COMPACTION_HOLD_MS = 3_600_000L
