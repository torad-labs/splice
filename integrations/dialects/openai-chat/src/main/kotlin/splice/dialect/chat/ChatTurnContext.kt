// PORT-OF: ChatStreamTranslator.kt @ e2e0d0f — invariants unchanged: verbatim move, zero
// behavioural coupling to the translator (it is only a ctor parameter).
package splice.dialect.chat

import splice.core.failure.LoginOwner
import splice.dialect.chat.failure.ChatReplies
import splice.upstream.ClientGone
import splice.upstream.WatchdogProbe
import splice.upstream.failure.Replies

public data class ChatTurnContext(
    val clientGone: ClientGone,
    val watchdogFired: WatchdogProbe,
    val idleCapMs: Long,
    val totalCapMs: Long,
    /** The head's reading of its own failures (failure-handling slice 3): an error chunk is read once, by the reader
     *  that reads the head's failed replies. */
    val replies: Replies = Replies(ChatReplies),
    /** Whose login the head sends, which names the owner of a login an error chunk rejects. */
    val owner: LoginOwner = LoginOwner.SPLICE,
)
