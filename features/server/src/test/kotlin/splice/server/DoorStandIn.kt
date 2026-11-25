// The team's door as a test stands it in (gateway-no-heads, TeamGateway): every turn is routed to the provider `box`,
// which `claude-team` names, and served at once with a short answer, while what the listener said of each turn
// (TeamTurnMeta) is kept for the test to read. Given the [board] its heads say on, it says how each turn went, as a
// provider's head does. What the gateway itself does with a turn is the daemon's to test.
package splice.server

import io.ktor.http.ContentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import kotlinx.coroutines.delay
import splice.core.server.ServedTokens
import splice.core.server.ServedTurn
import splice.core.server.ServedTurns
import splice.core.server.TurnPace
import splice.share.RefusalVoice
import java.util.concurrent.CopyOnWriteArrayList

internal class DoorStandIn : TeamGateway {
    /** What the listener said of each turn it handed the door, in the order it handed them. */
    val said = CopyOnWriteArrayList<TeamTurnMeta>()

    /** The board the door's heads say on how each turn went; none says nothing. */
    @Volatile
    var board: ServedTurns? = null

    /** Whether each turn's head says the server's deadline on its silence ended it (#791). */
    @Volatile
    var timesOut: Boolean = false

    /** What each turn's head says it billed: its tokens and its price; nothing by default. */
    @Volatile
    var billed: Pair<ServedTokens, Double>? = null

    /** How long each turn's relay goes on once its answer is out, as one that hangs up on its upstream after
     *  answering does; none by default. */
    @Volatile
    var finishingMs: Long = 0

    override suspend fun routed(
        call: ApplicationCall,
        voice: RefusalVoice,
        counting: Boolean,
        serves: ProviderFilter,
    ): RoutedTeamTurn = object : RoutedTeamTurn {
        override val provider: String = "box"

        override val names: Set<String> = setOf("box", "claude-team")

        override suspend fun serve(meta: TeamTurnMeta) {
            said += meta
            val id = meta.servedTurn
            if (id != null) board?.ended(id, served())
            call.respondText(DOOR_ANSWER, ContentType.Application.Json)
            delay(finishingMs)
        }
    }

    override suspend fun models(call: ApplicationCall, voice: RefusalVoice, serves: ProviderFilter) {
        call.respondText("""{"data":[]}""", ContentType.Application.Json)
    }

    /** How a turn went, as its head says it: one whose answer stalled after its first word, when [timesOut]. */
    private fun served(): ServedTurn = ServedTurn(
        model = "box-model",
        upstream = null,
        outcome = if (timesOut) STALLED_OUTCOME else "ok",
        tokens = billed?.first,
        costUsd = billed?.second,
        pace = TurnPace(ttftMs = 1, timedOut = timesOut),
        speed = null,
    )
}

internal const val DOOR_ANSWER = """{"type":"message","content":[{"type":"text","text":"from the door"}]}"""

// The outcome a head writes for a turn whose answer stalled once its client had seen some of it.
private const val STALLED_OUTCOME = "failure:overloaded_error"
