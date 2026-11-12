package splice.control.mcp.hub

import splice.control.mcp.JsonRpcCodec
import splice.core.util.JsonScalars

internal data class ReplacementStart(
    val server: String,
    val launch: String,
    val oldSocket: String,
    val oldGeneration: String,
)

internal data class ReplacementValues(
    val old: ReplacementStart,
    val candidateSocket: String,
    val candidateTuple: String,
    val candidateGeneration: String,
    val claim: String?,
)

internal data class ParsedDrainPin(
    val request: HubDrainPinRequest?,
    val claim: String?,
    val error: String?,
)

internal data class ParsedHolderExit(val socket: String?, val generation: String?)

internal class HubReplacementParser(private val codec: JsonRpcCodec) {
    fun start(body: String): Pair<ReplacementStart?, String?> {
        val parsed = codec.parse(body)
        val server = JsonScalars.str(parsed, HubFields.SERVER)?.takeIf { it.isNotEmpty() }
        val socket = JsonScalars.str(parsed, HubFields.OLD_SOCKET)
        val generation = JsonScalars.str(parsed, HubFields.OLD_GENERATION)
        val error = when {
            server == null -> "the replacement names no server"
            socket == null -> "the replacement names no old holder socket"
            generation == null -> "the replacement names no old holder generation"
            else -> null
        }
        val request = if (error == null) {
            ReplacementStart(
                server.orEmpty(),
                JsonScalars.str(parsed, HubFields.LAUNCH).orEmpty(),
                socket.orEmpty(),
                generation.orEmpty(),
            )
        } else {
            null
        }
        return request to error
    }

    fun pin(body: String): ParsedDrainPin {
        val parsed = codec.parse(body)
        val server = JsonScalars.str(parsed, HubFields.SERVER)?.takeIf { it.isNotEmpty() }
        val socket = JsonScalars.str(parsed, HubFields.OLD_SOCKET)
        val generation = JsonScalars.str(parsed, HubFields.OLD_GENERATION)
        val error = when {
            server == null -> "the drain ownership claim names no server"
            socket == null -> "the drain ownership claim names no old holder socket"
            generation == null -> "the drain ownership claim names no old holder generation"
            else -> null
        }
        val request = if (error == null) {
            HubDrainPinRequest(
                server.orEmpty(),
                JsonScalars.str(parsed, HubFields.LAUNCH).orEmpty(),
                socket.orEmpty(),
                generation.orEmpty(),
            )
        } else {
            null
        }
        return ParsedDrainPin(request, JsonScalars.str(parsed, HubFields.CLAIM), error)
    }

    fun values(body: String): ReplacementValues? {
        val parsed = codec.parse(body)
        val server = JsonScalars.str(parsed, HubFields.SERVER)?.takeIf { it.isNotEmpty() }
        val oldSocket = JsonScalars.str(parsed, HubFields.OLD_SOCKET)
        val oldGeneration = JsonScalars.str(parsed, HubFields.OLD_GENERATION)
        val candidateSocket = JsonScalars.str(parsed, HubFields.CANDIDATE_SOCKET)
        val candidateTuple = JsonScalars.str(parsed, HubFields.CANDIDATE_TUPLE)
        val candidateGeneration = JsonScalars.str(parsed, HubFields.CANDIDATE_GENERATION)
        val required = listOf(server, oldSocket, oldGeneration, candidateSocket, candidateTuple, candidateGeneration)
        return if (required.any { it == null }) {
            null
        } else {
            ReplacementValues(
                ReplacementStart(
                    server.orEmpty(),
                    JsonScalars.str(parsed, HubFields.LAUNCH).orEmpty(),
                    oldSocket.orEmpty(),
                    oldGeneration.orEmpty(),
                ),
                candidateSocket.orEmpty(),
                candidateTuple.orEmpty(),
                candidateGeneration.orEmpty(),
                JsonScalars.str(parsed, HubFields.OWNER_CLAIM),
            )
        }
    }

    fun exit(body: String): ParsedHolderExit {
        val parsed = codec.parse(body)
        return ParsedHolderExit(
            JsonScalars.str(parsed, HubFields.SOCKET),
            JsonScalars.str(parsed, HubFields.GENERATION),
        )
    }
}
