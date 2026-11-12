package splice.control.mcp.hub

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.client.mcp.McpSharing
import splice.control.mcp.JsonRpcCodec
import splice.control.mcp.McpHandshakeBudget
import splice.control.mcp.McpResult
import splice.core.util.JsonScalars
import splice.core.util.JsonWire
import splice.core.util.LogSafe
import splice.core.util.LogSink
import splice.core.wire.HttpStatus

internal class HubRequests(
    private val targets: HubTargets,
    private val holders: HubHolders,
    private val listings: HubListings,
    private val sharing: McpSharing,
    private val startBound: McpHandshakeBudget,
    private val log: LogSink,
    private val sweep: HubSweep,
) {
    private val codec = JsonRpcCodec()

    fun resolve(body: String): McpHubReply {
        val targeted = targets.target(body)
        return targeted.target?.let(targets::found) ?: targets.refused(targeted)
    }

    /** Attestation reads only the recorded holder owner and never reconstructs a target from current configuration. */
    fun attest(body: String): McpHubReply {
        val socket = JsonScalars.str(codec.parse(body), HubFields.SOCKET)?.takeIf { it.isNotEmpty() }
            ?: return targets.error(HttpStatus.BAD_REQUEST, "the attestation names no holder socket")
        val generation = holders.generation(socket)
            ?: return targets.error(HttpStatus.SERVICE_UNAVAILABLE, "the holder socket has no live owner generation")
        return McpHubReply(
            HttpStatus.OK,
            JsonWire.string(
                buildJsonObject {
                    put(HubFields.SOCKET, socket)
                    put(HubFields.GENERATION, generation)
                },
            ),
        )
    }

    suspend fun start(body: String): McpHubReply {
        val targeted = targets.target(body)
        val target = targeted.target ?: return targets.refused(targeted)
        return when (val configured = targets.launch(target)) {
            is McpResult.Served -> start(target, configured.value)
            is McpResult.Refused -> targets.error(HttpStatus.INTERNAL_SERVER_ERROR, configured.reason)
        }
    }

    fun listing(body: String): McpHubReply {
        val targeted = targets.target(body)
        val target = targeted.target ?: return targets.refused(targeted)
        val parsed = codec.parse(body)
        val initialize = parsed?.get("initialize") as? JsonObject
            ?: return targets.error(HttpStatus.BAD_REQUEST, "the listing names no initialize result")
        listings.keep(target.listing, initialize, parsed["tools"] as? JsonObject)
        return McpHubReply(HubResponses.NO_CONTENT, null)
    }

    fun roots(body: String): McpHubReply {
        val targeted = targets.target(body)
        val target = targeted.target ?: return targets.refused(targeted)
        if (target.shared) rootsAsked(target)
        return McpHubReply(HubResponses.NO_CONTENT, null)
    }

    fun sweep(now: Long, idleMs: Long) = sweep.sweep(now, idleMs)

    fun attached(front: HubFront): Map<String, Set<String>> {
        val bindings = front.report()?.bindings ?: return emptyMap()
        val names = holders.records.files.serverBySocket()
        return bindings.filter { it.launch.isNotEmpty() }
            .mapNotNull { binding -> names[binding.socket]?.let { it to binding.launch } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, launches) -> launches.toSet() }
    }

    private suspend fun start(target: HubTarget, run: HubLaunchSpec): McpHubReply {
        val holder = HubStart(target.key, target.spec.name, target.launch, run, targets.fingerprint(run))
        return when (val result = holders.start(holder, startBound())) {
            is McpResult.Served -> targets.found(target)
            is McpResult.Refused -> targets.error(HttpStatus.INTERNAL_SERVER_ERROR, result.reason)
        }
    }

    private fun rootsAsked(target: HubTarget) {
        sharing.rootsAsked(target.spec)
        log(
            "[mcp-hub] ${LogSafe.str(target.spec.name)}: asks its client for roots, " +
                "so each later session starts its own\n",
        )
    }
}
