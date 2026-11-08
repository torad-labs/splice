package splice.app.peer.codex

import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.serialization.json.JsonObject
import splice.sessions.peer.codex.CodexThread

/** A connection to the client that owns a loaded thread, including its lifecycle. */
internal interface CodexLiveThread : CodexThread {
    val notifications: ReceiveChannel<CodexEvent>

    suspend fun read(): String

    suspend fun greet(): CodexGreeting

    fun mine(params: JsonObject?): Boolean

    fun close()
}
