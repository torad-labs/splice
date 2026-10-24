package splice.hub.front

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The persisted and management views of a replacement drain share the same stored contract. */
internal class HolderDrainViews(
    private val store: HolderReplacementStore,
    private val details: HolderCandidateDetails,
) {
    fun state(): JsonObject = JsonObject(store.drains.mapValues { (_, drain) -> drain.state() })

    fun restore(saved: JsonObject?) {
        saved?.forEach { (_, value) -> details.restore(store, value as? JsonObject) }
    }

    fun status(server: String, launch: String, outstanding: Int, attached: Int): JsonObject? {
        val drain = store.drains["$server\n$launch"] ?: return null
        return buildJsonObject {
            put(
                "target",
                buildJsonObject {
                    put(SERVER, drain.server)
                    put(LAUNCH, drain.launch)
                },
            )
            put("captured_socket", drain.socket)
            put("captured_generation", drain.generation)
            put(Rpc.PROTOCOL, drain.protocolVersion)
            put(CAPABILITIES, drain.capabilities)
            put("admission", if (drain.phase in pausingPhases) "paused" else "open")
            put("wire_outstanding", outstanding)
            put("clients_attached", attached)
            put("settled", outstanding == 0)
            put("replacement", replacementView(drain))
        }
    }

    private fun replacementView(drain: HolderDrain): JsonObject = buildJsonObject {
        put("state", drain.phase)
        drain.candidate?.let { candidate ->
            put(CANDIDATE_SOCKET, candidate.socket)
            put(CANDIDATE_TUPLE, candidate.tuple)
            put(CANDIDATE_GENERATION, candidate.generation)
            put(CANDIDATE_SHARED, candidate.shared)
        }
        (drain.blocker ?: NO_REPLACEMENT_PRIMITIVE.takeIf { drain.phase == PAUSED })?.let {
            put("blocker", it)
        }
    }
}
