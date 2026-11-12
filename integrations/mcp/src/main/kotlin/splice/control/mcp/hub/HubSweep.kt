package splice.control.mcp.hub

import splice.client.mcp.McpSharing

// why: a newly unbound shared holder gets five minutes for the front and launch state to converge before retirement.
private const val SHARED_GRACE_MS = 300_000L
private const val ENDED = "its session ended"
private const val IDLE_REASON = "no session called it within the MCP idle limit"
private const val ORPHANED = "no current plan names it and no live session is bound to it"

private data class SharedView(
    val bound: List<HubBinding>?,
    val current: Set<String>,
    val draining: Set<HubDrainHolder>,
    val lastCalls: Map<String, Long>,
    val idleMs: Long,
    val now: Long,
) {
    fun stopReason(record: HubRecord): String? {
        val bindings = bound ?: return null
        if (pinned(record)) return null
        return idleReason(record) ?: orphanReason(record, bindings)
    }

    private fun pinned(record: HubRecord): Boolean =
        record.generation?.let { HubDrainHolder(record.socket, it) in draining } ?: false

    private fun idleReason(record: HubRecord): String? =
        lastCalls[record.socket]?.takeIf { now - maxOf(it, record.recordedAt) >= idleMs }?.let { IDLE_REASON }

    private fun orphanReason(record: HubRecord, bindings: List<HubBinding>): String? =
        ORPHANED.takeIf { unbound(record, bindings) && now - record.recordedAt >= SHARED_GRACE_MS }

    private fun unbound(record: HubRecord, bindings: List<HubBinding>): Boolean =
        bindings.none { it.socket == record.socket } && record.socket !in current
}

internal class HubSweep(
    private val sharing: McpSharing,
    private val servers: McpLaunchServers,
    private val places: McpLaunchPlaces,
    private val tuple: HubTuple,
    private val holders: HubHolders,
    private val front: HubFront,
) {
    fun sweep(now: Long, idleMs: Long) {
        holders.stopUnkept { record -> stopReason(record, now, idleMs) }
    }

    /** Refreshes both sides of drain protection inside the holder's destructive lifecycle lock. */
    private fun stopReason(record: HubRecord, now: Long, idleMs: Long): String? {
        val report = front.report() ?: return null
        if (!holders.pinStoreReadable()) return null
        val retained = record.generation?.let { generation ->
            val identity = HubDrainHolder(record.socket, generation)
            identity in report.draining || identity in report.outstanding
        } ?: false
        return if (holders.pinned(record.socket, record.generation, report.claims) || retained) {
            null
        } else {
            val launch = record.launch
            if (launch != null) {
                ENDED.takeIf { places.of(launch) == null }
            } else {
                sharedView(report, now, idleMs).stopReason(record)
            }
        }
    }

    private fun sharedView(report: HubReport, now: Long, idleMs: Long): SharedView {
        val bound = report.bindings.filter { it.launch.isEmpty() || places.of(it.launch) != null }
        return SharedView(bound, currentShared(bound), report.draining, report.lastCalls, idleMs, now)
    }

    private fun currentShared(bound: List<HubBinding>): Set<String> {
        val launches = bound.map { it.launch }.toSet() + ""
        return launches.flatMap { launch ->
            val place = launch.takeIf { it.isNotEmpty() }?.let(places::of)
            sharing.plan(servers.servers(launch, place)).hosted.values
                .map { holders.socket(tuple.digest(it)).toString() }
        }.toSet()
    }
}
