package splice.control.mcp.hub

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.control.mcp.JsonRpcCodec
import splice.control.mcp.McpHandshakeBudget
import splice.control.mcp.McpResult
import splice.core.util.JsonWire
import splice.core.wire.HttpStatus

internal fun interface ReplacementAction {
    operator fun invoke(values: ReplacementValues): McpResult<Unit>
}

internal class McpHubReplacements(
    codec: JsonRpcCodec,
    private val targets: HubTargets,
    tuple: HubTuple,
    private val holders: HubHolders,
    front: HubFront,
    private val startBound: McpHandshakeBudget,
) {
    private val parser = HubReplacementParser(codec)
    private val canonical = HubCanonicalReplacements(targets, tuple, holders, front)

    suspend fun start(body: String): McpHubReply {
        val (request, error) = parser.start(body)
        return if (request == null) targets.error(HttpStatus.BAD_REQUEST, error.orEmpty()) else start(request)
    }

    private suspend fun start(request: ReplacementStart): McpHubReply {
        val replacement = when (val result = canonical.resolve(request)) {
            is McpResult.Served -> result.value
            is McpResult.Refused -> return targets.error(HttpStatus.SERVICE_UNAVAILABLE, result.reason)
        }
        return if (replacement.socket == request.oldSocket) {
            targets.error(
                HttpStatus.SERVICE_UNAVAILABLE,
                "the canonical configured tuple still resolves to the old holder",
            )
        } else {
            started(replacement)
        }
    }

    fun commit(body: String): McpHubReply {
        val values = parser.values(body)
            ?: return targets.error(HttpStatus.BAD_REQUEST, "the replacement names an incomplete holder tuple")
        val replacement = when (
            val result = canonical.resolve(values.old)
        ) {
            is McpResult.Served -> result.value
            is McpResult.Refused -> return targets.error(HttpStatus.SERVICE_UNAVAILABLE, result.reason)
        }
        return if (matches(replacement, values)) {
            McpHubReply(HubResponses.NO_CONTENT, null)
        } else {
            targets.error(
                HttpStatus.SERVICE_UNAVAILABLE,
                "the candidate no longer matches every affected canonical alias and launch",
            )
        }
    }

    fun retire(body: String): McpHubReply = action(body, ReplacementAction(holders::retire))

    fun rollback(body: String): McpHubReply = action(body, ReplacementAction(holders::rollback))

    fun exit(body: String): McpHubReply {
        val values = parser.exit(body)
        val socket = values.socket
            ?: return targets.error(HttpStatus.BAD_REQUEST, "the holder exit names no socket")
        val generation = values.generation
            ?: return targets.error(HttpStatus.BAD_REQUEST, "the holder exit names no generation")
        return result(holders.exited(socket, generation))
    }

    private suspend fun started(replacement: CanonicalReplacement): McpHubReply = when (
        val started = holders.start(
            HubStart(
                replacement.target.key,
                replacement.target.spec.name,
                replacement.target.launch,
                replacement.run,
                replacement.tuple,
            ),
            startBound(),
        )
    ) {
        is McpResult.Refused -> targets.error(HttpStatus.SERVICE_UNAVAILABLE, started.reason)
        is McpResult.Served -> response(replacement)
    }

    private fun response(replacement: CanonicalReplacement): McpHubReply {
        val generation = holders.generation(replacement.socket)
            ?: return targets.error(
                HttpStatus.SERVICE_UNAVAILABLE,
                "the candidate holder has no live owner generation",
            )
        return if (holders.owns(replacement.socket, replacement.tuple, generation)) {
            val body = buildJsonObject {
                put(HubFields.SOCKET, replacement.socket)
                put(HubFields.TUPLE, replacement.tuple)
                put(HubFields.GENERATION, generation)
                put(HubFields.SHARED, replacement.target.shared)
            }
            McpHubReply(HttpStatus.OK, JsonWire.string(body))
        } else {
            targets.error(
                HttpStatus.SERVICE_UNAVAILABLE,
                "the candidate holder does not prove the canonical configured tuple",
            )
        }
    }

    private fun action(body: String, operation: ReplacementAction): McpHubReply {
        val values = parser.values(body)
            ?: return targets.error(HttpStatus.BAD_REQUEST, "the replacement names an incomplete holder tuple")
        return result(operation(values))
    }

    private fun matches(replacement: CanonicalReplacement, values: ReplacementValues): Boolean =
        replacement.socket == values.candidateSocket &&
            replacement.tuple == values.candidateTuple &&
            holders.owns(values.candidateSocket, values.candidateTuple, values.candidateGeneration)

    private fun result(value: McpResult<Unit>): McpHubReply = when (value) {
        is McpResult.Served -> McpHubReply(HubResponses.NO_CONTENT, null)
        is McpResult.Refused -> targets.error(HttpStatus.SERVICE_UNAVAILABLE, value.reason)
    }
}
