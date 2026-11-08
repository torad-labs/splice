package splice.app.peer.codex

import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.serialization.json.JsonObject
import splice.sessions.peer.codex.CodexThread

/** The thread identity field shared by app-server requests and normalized lifecycle notifications. */
internal const val CODEX_THREAD_ID_FIELD = "threadId"

/** A connection to the client that owns a loaded thread, including its lifecycle. */
internal interface CodexLiveThread : CodexThread {
    val notifications: ReceiveChannel<CodexEvent>

    suspend fun read(): String

    suspend fun greet(): CodexGreeting

    fun mine(params: JsonObject?): Boolean

    fun close()
}
