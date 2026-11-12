package splice.app.peer.codex

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import splice.core.util.JsonScalars
import splice.sessions.peer.codex.TurnStart

// why: the desktop uses version 2 for a local follower's turn request; no remote host override is sent.
private const val START_VERSION = 2

// why: as with app-server turn/start, wait for admission, not for the model to finish its turn.
private const val START_TIMEOUT_MS = 60_000L

/** Plain user input inherits the existing desktop thread's model, effort, permissions and workspace. */
internal class CodexDesktopTurn(private val rpc: CodexIpcRpc, private val thread: String, private val owner: String) {
    suspend fun start(clientId: String, text: String): TurnStart {
        val answer = rpc.request(
            "thread-follower-start-turn",
            params(clientId, text),
            START_VERSION,
            START_TIMEOUT_MS,
            owner,
        )
        return when (answer) {
            is CodexIpcAnswer.Result -> admitted(answer.value)
            // An owner can report an error after its app-server accepted the turn. IPC does not carry that boundary,
            // so do not describe a generic error as proof of non-delivery or encourage a duplicate send.
            is CodexIpcAnswer.Error -> TurnStart.Unknown("the desktop did not confirm delivery: ${answer.reason}")
            is CodexIpcAnswer.Lost -> {
                if (answer.sent) TurnStart.Unknown(answer.reason) else TurnStart.NotSent(answer.reason)
            }
        }
    }

    private fun params(clientId: String, text: String): JsonObject = buildJsonObject {
        put("conversationId", thread)
        putJsonObject("turnStart") {
            putJsonObject("request") {
                put("threadId", thread)
                put("clientUserMessageId", clientId)
                putJsonArray("input") {
                    add(
                        buildJsonObject {
                            put("type", "text")
                            put("text", text)
                            put("text_elements", buildJsonArray { })
                        },
                    )
                }
            }
            putJsonObject("context") { put("inheritThreadSettings", true) }
        }
    }

    private fun admitted(response: JsonObject): TurnStart {
        if (JsonScalars.str(response, "handledByClientId") != owner) {
            return TurnStart.Unknown("a different desktop client answered the turn request")
        }
        val result = (response["result"] as? JsonObject)?.get("result") as? JsonObject
        return JsonScalars.str(result?.get("turn") as? JsonObject, "id")?.let { TurnStart.Started(it) }
            ?: TurnStart.Unknown("the desktop answered without a turn id")
    }
}
