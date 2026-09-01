// PORT-OF: splice/app/Daemon.kt (HeadLifecycle.assembleDaemonHeads, .logUsageKeyCollisions) @
// ed5c868 — invariants unchanged: the port-collision/invalid-port pre-checks and the per-head
// try-build loop, split from HeadLifecycle's other two boot/shutdown phases into their own class
// (2026-08-17 decomposition).
package splice.app.head

import splice.app.DaemonBoundary
import splice.app.HeadAssembly
import splice.control.ManagedHead
import splice.core.config.StatePaths
import splice.core.topology.Topology
import splice.core.topology.TopologyMessages
import splice.core.util.LogSink

internal class HeadBoot {

    private val boundary = DaemonBoundary()

    internal fun assembleDaemonHeads(
        topology: Topology,
        statePaths: StatePaths,
        heads: MutableMap<String, ManagedHead>,
        log: LogSink,
        assemble: HeadAssembly,
    ): LinkedHashMap<String, String> {
        val failed = LinkedHashMap<String, String>()
        // CTL-005: name an out-of-range port before the head hits an opaque bind-time error.
        val invalidPorts = topology.invalidPortHeads()
        for ((key, port) in invalidPorts) {
            failed[key] = TopologyMessages.invalidPortMessage(key, port)
            log("[daemon][boot] ${TopologyMessages.invalidPortMessage(key, port)}\n")
        }
        // JW-13: name a duplicate-port collision before the loser hits an opaque "Address already in
        // use". Both colliding heads are marked failed with a message pointing at the sibling.
        val portDupes = topology.portCollisions()
        val collidingHeads = portDupes.values.flatten().toSet()
        for ((port, keys) in portDupes) {
            keys.forEach { failed[it] = TopologyMessages.portCollisionMessage(port, keys) }
            log("[daemon][boot] ${TopologyMessages.portCollisionMessage(port, keys)}\n")
        }
        // Invalid-port and colliding heads already failed above with a named reason — filter them
        // out so the assembly loop keeps a single continue (detekt LoopWithTooManyJumpStatements).
        for ((key, head) in topology.heads.filterKeys { it !in collidingHeads && it !in invalidPorts.keys }) {
            val providerCfg = topology.providers[head.provider]
            if (providerCfg == null) {
                failed[key] = "unknown provider '${head.provider}'"
                log("[$key][boot] SKIPPED: unknown provider '${head.provider}'\n")
                continue
            }
            boundary.runCatchingDaemonBoundary { assemble(key, head, providerCfg) }
                .onSuccess { heads[key] = it }
                .onFailure {
                    failed[key] = boundary.reason(it)
                    // SAFE-RENDER-EXEMPT[2026-08-31]: runCatchingDaemonBoundary catches a CLOSED set (IOException, IllegalArgumentException, IllegalStateException) and assembly only WIRES objects from already-parsed topology — it opens no credential file, so no parser excerpt can arrive here; routing it withheld the rejected auth/dialect tuple that AuthDialectCompatibilityBootTest pins as the operator's only diagnosis of a failed head
                    log("[$key][boot] SKIPPED (build failed): ${it.message}\n")
                }
        }
        // IO-006: heads above that alias to the same legacy usage file (deliberate migration
        // continuity, not a bug on its own — see [logUsageKeyCollisions]) share it with no
        // cross-process coordination if both run at once.
        logUsageKeyCollisions(statePaths, heads.keys, log)
        return failed
    }

    /** IO-006: neither head is refused (that would break the codex/claudex usage-history migration
     *  the alias exists for) — the collision is only named, loudly, same idiom as JW-13's
     *  [portCollisionMessage]. */
    private fun logUsageKeyCollisions(statePaths: StatePaths, headKeys: Collection<String>, log: LogSink) {
        statePaths.usageKeyCollisions(headKeys).forEach { (statKey, keys) ->
            log(
                "[daemon][boot] WARNING: heads ${keys.joinToString(" and ")} share the '$statKey' usage/ratelimit " +
                    "files with no cross-process write coordination — quota numbers may race\n",
            )
        }
    }
}
