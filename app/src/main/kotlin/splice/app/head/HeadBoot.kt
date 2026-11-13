// PORT-OF: splice/app/Daemon.kt (HeadLifecycle.assembleDaemonHeads, .logUsageKeyCollisions) @
// ed5c868 — invariants unchanged: the port-collision/invalid-port pre-checks and the per-head
// try-build loop, split from HeadLifecycle's other two boot/shutdown phases into their own class
// (2026-08-17 decomposition).
//
// HEADS SERVE GROUP BY GROUP (#525, 2026-11-13). Every head was assembled before any was started. A head that keeps
// code-mode state reads all of it while it is assembled, about three seconds where a few hundred MiB are saved, and
// each restart held every other head's requests behind that read. The heads that keep none are now assembled and
// started first, and the ones that read are the last group.
package splice.app.head

import splice.app.DaemonBoundary
import splice.app.HeadAssembly
import splice.app.HeadsStart
import splice.app.control.ManagedHead
import splice.core.config.StatePaths
import splice.core.topology.AuthCompatibility
import splice.core.topology.HeadConfig
import splice.core.topology.Topology
import splice.core.topology.TopologyMessages
import splice.core.util.LogSink

internal class HeadBoot(
    /** Starts each group once it is assembled. A caller that only assembles hands none. */
    private val start: HeadsStart = HeadsStart { _, _ -> },
) {

    private val boundary = DaemonBoundary()

    /** [heads] is filled group by group, so a caller that lists it hands a map that keeps its own order. */
    internal suspend fun assembleDaemonHeads(
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
        val bootable = topology.heads.filterKeys { it !in collidingHeads && it !in invalidPorts.keys }
        // A stable split: each group keeps splice.toml's order.
        val (reading, quick) = bootable.entries.partition { (_, head) ->
            topology.providers[head.provider]?.codeModeEnabled == true
        }
        for (group in listOf(quick, reading)) {
            val built = assembleGroup(topology, group, failed, log, assemble)
            heads.putAll(built)
            start(built, failed)
        }
        // IO-006: heads above that alias to the same legacy usage file (deliberate migration
        // continuity, not a bug on its own — see [logUsageKeyCollisions]) share it with no
        // cross-process coordination if both run at once.
        logUsageKeyCollisions(statePaths, heads.keys, log)
        return failed
    }

    private fun assembleGroup(
        topology: Topology,
        group: List<Map.Entry<String, HeadConfig>>,
        failed: MutableMap<String, String>,
        log: LogSink,
        assemble: HeadAssembly,
    ): Map<String, ManagedHead> {
        val built = LinkedHashMap<String, ManagedHead>()
        for ((key, head) in group) {
            val providerCfg = topology.providers[head.provider]
            val unbootable = if (providerCfg == null) {
                "unknown provider '${head.provider}'"
            } else {
                AuthCompatibility.refusal(key, head.provider, providerCfg)
            }
            if (unbootable != null) {
                failed[key] = unbootable
                log("[$key][boot] SKIPPED: $unbootable\n")
                continue
            }
            boundary.runCatchingDaemonBoundary { assemble(key, head, checkNotNull(providerCfg)) }
                .onSuccess {
                    built[key] = it
                    logUnlisted(key, head, it, log)
                }
                .onFailure {
                    val reason = boundary.reason(it)
                    failed[key] = reason
                    log("[$key][boot] SKIPPED (build failed): $reason\n")
                }
        }
        return built
    }

    /** 2026-09-23: a model the head's `models` list names that its catalog does not carry was not
     *  listed by the endpoint at this start (retired, filtered out, or not answered in time). The
     *  topology drops that row rather than the head; this line is where the operator learns of it. */
    private fun logUnlisted(key: String, head: HeadConfig, built: ManagedHead, log: LogSink) {
        val catalog = built.statusline.catalog ?: return
        val unlisted = head.models.orEmpty().map { it.id }.filterNot(catalog::contains)
        if (unlisted.isNotEmpty()) {
            log(
                "[$key][boot] models list names ${unlisted.joinToString(", ")}, which its endpoint did not " +
                    "list at this start, so they are not offered until it does\n",
            )
        }
        head.tierModels().filterValues { id -> catalog.live().models.none { it.id == id } }.forEach { (slot, id) ->
            log("[$key][boot] model_slots.$slot names $id, which is not offered at this start; $slot stays unmapped\n")
        }
    }

    /** IO-006: neither head is refused (that would break the codex/claudex usage-history migration
     *  the alias exists for) — the collision is only named, loudly, same idiom as JW-13's
     *  [portCollisionMessage]. */
    private fun logUsageKeyCollisions(statePaths: StatePaths, headKeys: Collection<String>, log: LogSink) {
        statePaths.usageKeyCollisions(headKeys).forEach { (statKey, keys) ->
            log(
                "[daemon][boot] WARNING: heads ${keys.joinToString(" and ")} share the '$statKey' usage/ratelimit " +
                    "files with no cross-process write coordination, so quota numbers may race\n",
            )
        }
    }
}
