package splice.control.mcp.hub

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.control.mcp.JsonRpcCodec
import splice.control.mcp.McpResult
import splice.core.util.JsonWire
import splice.core.wire.HttpStatus

/** The daemon side of the front's durable drain-claim handshake. */
internal class McpHubDrainPins(
    codec: JsonRpcCodec,
    private val targets: HubTargets,
    private val holders: HubHolders,
    private val front: HubFront,
) {
    private val parser = HubReplacementParser(codec)

    fun acquire(body: String): McpHubReply {
        val parsed = parser.pin(body)
        val request = parsed.request ?: return targets.error(HttpStatus.BAD_REQUEST, parsed.error.orEmpty())
        return when (val acquired = holders.acquirePin(request, claims())) {
            is McpResult.Served -> McpHubReply(
                HttpStatus.OK,
                JsonWire.string(buildJsonObject { put(HubFields.CLAIM, acquired.value.claim) }),
            )
            is McpResult.Refused -> targets.error(HttpStatus.SERVICE_UNAVAILABLE, acquired.reason)
        }
    }

    fun confirm(body: String): McpHubReply {
        val parsed = parser.pin(body)
        val request = parsed.request ?: return targets.error(HttpStatus.BAD_REQUEST, parsed.error.orEmpty())
        val claim = parsed.claim?.takeIf { it.isNotEmpty() }
            ?: return targets.error(HttpStatus.BAD_REQUEST, "the drain ownership claim names no claim")
        return result(holders.confirmPin(request, claim, claims()))
    }

    fun release(body: String): McpHubReply {
        val parsed = parser.pin(body)
        val request = parsed.request ?: return targets.error(HttpStatus.BAD_REQUEST, parsed.error.orEmpty())
        val claim = parsed.claim?.takeIf { it.isNotEmpty() }
            ?: return targets.error(HttpStatus.BAD_REQUEST, "the drain ownership claim names no claim")
        return result(holders.releasePin(request, claim))
    }

    private fun claims(): Set<HubDrainClaim> = front.report()?.claims.orEmpty()

    private fun result(value: McpResult<Unit>): McpHubReply = when (value) {
        is McpResult.Served -> McpHubReply(HubResponses.NO_CONTENT, null)
        is McpResult.Refused -> targets.error(HttpStatus.SERVICE_UNAVAILABLE, value.reason)
    }
}
