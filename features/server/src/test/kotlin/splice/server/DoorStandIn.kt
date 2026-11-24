// The team's door as a test stands it in (gateway-no-heads, TeamGateway): every turn is routed to the provider `box`,
// which `claude-team` names, and served at once with a short answer, while what the listener said of each turn
// (TeamTurnMeta) is kept for the test to read. What the gateway itself does with a turn is the daemon's to test.
package splice.server

import io.ktor.http.ContentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import splice.share.RefusalVoice
import java.util.concurrent.CopyOnWriteArrayList

internal class DoorStandIn : TeamGateway {
    /** What the listener said of each turn it handed the door, in the order it handed them. */
    val said = CopyOnWriteArrayList<TeamTurnMeta>()

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
            call.respondText(DOOR_ANSWER, ContentType.Application.Json)
        }
    }

    override suspend fun models(call: ApplicationCall, voice: RefusalVoice, serves: ProviderFilter) {
        call.respondText("""{"data":[]}""", ContentType.Application.Json)
    }
}

internal const val DOOR_ANSWER = """{"type":"message","content":[{"type":"text","text":"from the door"}]}"""
