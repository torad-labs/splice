package splice.hub.front

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The daemon serializes these old-generation claims with destructive idle eviction. */
internal class RoutePins(private val daemon: Daemon) {
    fun acquire(drain: HolderDrain, done: (String?, String?) -> Unit) {
        call(drain, "acquire", null) { status, text ->
            val claim = Rpc.str(JsonText.objectOrNull(text), "claim")?.takeIf(String::isNotEmpty)
            when {
                status !in OK..NO_CONTENT -> done(
                    null,
                    Rpc.daemonWords(text) ?: "the owner did not pin this generation",
                )
                claim == null -> done(null, "the owner returned no drain claim")
                else -> done(claim, null)
            }
        }
    }

    fun confirm(drain: HolderDrain, claim: String, done: (String?) -> Unit) =
        call(drain, "confirm", claim) { status, text ->
            done(refusal(status, text, "the owner did not confirm the retained drain"))
        }

    fun release(drain: HolderDrain, claim: String, done: (String?) -> Unit) =
        call(drain, "release", claim) { status, text ->
            done(refusal(status, text, "the owner did not release the exact drain"))
        }

    private fun refusal(status: Int, text: String, fallback: String): String? =
        if (status in OK..NO_CONTENT) null else Rpc.daemonWords(text) ?: fallback

    private fun call(drain: HolderDrain, action: String, claim: String?, done: (Int, String) -> Unit) {
        val body = buildJsonObject {
            put(SERVER, drain.server)
            put(LAUNCH, drain.launch)
            put(OLD_SOCKET, drain.socket)
            put(OLD_GENERATION, drain.generation)
            claim?.let { put("claim", it) }
        }
        daemon.call("/api/mcp/hub/replacement/pin/$action", Rpc.encode(body), done)
    }
}
