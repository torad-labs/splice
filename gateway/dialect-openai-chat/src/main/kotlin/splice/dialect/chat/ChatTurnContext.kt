// PORT-OF: ChatStreamTranslator.kt @ e2e0d0f — invariants unchanged: verbatim move, zero
// behavioural coupling to the translator (it is only a ctor parameter).
package splice.dialect.chat

import splice.spi.ClientGone
import splice.spi.WatchdogProbe

public data class ChatTurnContext(
    val clientGone: ClientGone,
    val watchdogFired: WatchdogProbe,
    val idleCapMs: Long,
    val totalCapMs: Long,
)
