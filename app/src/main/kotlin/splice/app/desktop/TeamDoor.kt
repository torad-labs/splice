// NEW: gateway-no-heads. The door as a keyed team reaches it through the team listener (features/server, TeamDoorRoute):
// the same lineup, routing and serving as the other callers have, with no head. The listener admits the person by their
// key and applies their allowlist, caps, ledger and minimum client to the provider this resolves the selector to, aliases
// and old head prefixes included; this reads the request, finds that provider, and serves the turn on the provider's own
// serving with the metadata the listener authored, never trusting a header of the caller's. A turn's row reads as the
// terminal's: it is a `claude` session of this machine's door, wherever it was typed.
package splice.app.desktop

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import splice.app.desktop.catalog.DesktopLineup
import splice.app.desktop.catalog.DesktopProviders
import splice.app.desktop.catalog.DoorCatalog
import splice.app.provider.ServedProvider
import splice.core.client.SESSION_HEADER
import splice.core.topology.Topology
import splice.core.turn.TERMINAL_DOOR
import splice.core.util.JsonScalars
import splice.core.wire.HttpStatus
import splice.head.GatewayTurn
import splice.head.ListenerSays
import splice.http.JsonReply
import splice.server.ProviderFilter
import splice.server.RoutedTeamTurn
import splice.server.TeamGateway
import splice.server.TeamTurnMeta
import splice.share.CappedBodies
import splice.share.RefusalVoice

internal class TeamDoor : TeamGateway {
    private val json = Json { ignoreUnknownKeys = true }
    private val routing = DoorRouting()

    @Volatile
    private var opened: Opened? = null

    /** The providers a team is served, and the names an operator may call each by (TeamNames): `[server] commands` and
     *  a person's allowlist still name a head by its command or key, and now a provider by its key. */
    fun open(providers: DesktopProviders, topology: Topology) {
        val names = TeamNames(topology)
        opened = Opened(providers, topology.providers.keys.associateWith(names::of))
    }

    override suspend fun routed(
        call: ApplicationCall,
        voice: RefusalVoice,
        counting: Boolean,
        serves: ProviderFilter,
    ): RoutedTeamTurn? {
        val open = opened ?: return refused(call, voice, HttpStatus.SERVICE_UNAVAILABLE, STARTING)
        return routedOn(open, call, voice, counting, serves)
    }

    private suspend fun routedOn(
        open: Opened,
        call: ApplicationCall,
        voice: RefusalVoice,
        counting: Boolean,
        serves: ProviderFilter,
    ): RoutedTeamTurn? {
        val lineup = open.providers.lineup().serving { open.allows(it, serves) }
        val request = read(call, voice, lineup) ?: return null
        val model = JsonScalars.str(request, REQUEST_MODEL).orEmpty()
        val ask = DoorAsk(request, model, call.request.headers[SESSION_HEADER])
        val route = routing.of(lineup, ask, default = null, named = true)
            ?: return refused(call, voice, HttpStatus.BAD_REQUEST, "model \"$model\" is not served here")
        return Routed(call, route, ask, counting, open.namesOf(route.provider.key))
    }

    override suspend fun models(call: ApplicationCall, voice: RefusalVoice, serves: ProviderFilter) {
        val open = opened ?: return voice.refuse(call, HttpStatus.SERVICE_UNAVAILABLE, STARTING)
        val lineup = open.providers.lineup().serving { open.allows(it, serves) }
        // A team's clients are Claude Code in a terminal, whose 1M variant holds Claude Code's own million; `[desktop]`
        // states Claude Desktop's Code-tab windows and does not reach this list.
        JsonReply(HttpStatusCode.OK, DoorCatalog(lineup).discovery().toString()).send(call)
    }

    /** [call]'s body as a JSON object, under the widest bound a served provider takes; null once [call] is answered. */
    private suspend fun read(call: ApplicationCall, voice: RefusalVoice, lineup: DesktopLineup): JsonObject? {
        val widest = lineup.providers.maxByOrNull { it.maxRequestBytes }
            ?: return refused(call, voice, HttpStatus.BAD_REQUEST, "no provider is served on this server's door")
        val body = CappedBodies(voice).read(call, widest.maxRequestBytes, widest.requestReadTimeoutMs) ?: return null
        return JsonScalars.objectOrNull(json, body.decodeToString())
            ?: refused(call, voice, HttpStatus.BAD_REQUEST, "the request body is not a JSON object")
    }

    private suspend fun refused(call: ApplicationCall, voice: RefusalVoice, status: Int, why: String): Nothing? {
        voice.refuse(call, status, why)
        return null
    }

    private class Opened(val providers: DesktopProviders, private val names: Map<String, Set<String>>) {
        fun namesOf(provider: String): Set<String> = names[provider].orEmpty()

        fun allows(provider: ServedProvider, serves: ProviderFilter): Boolean =
            serves(provider.key, namesOf(provider.key))
    }

    /** A turn read and routed, served once the listener has admitted it. */
    private class Routed(
        private val call: ApplicationCall,
        private val route: DoorRoute,
        private val ask: DoorAsk,
        private val counting: Boolean,
        override val names: Set<String>,
    ) : RoutedTeamTurn {
        override val provider: String = route.provider.key

        override suspend fun serve(meta: TeamTurnMeta) {
            val turn = GatewayTurn(
                ask.asking(route.upstream),
                TERMINAL_DOOR,
                null,
                listener = ListenerSays(
                    meta.preferredUpstream,
                    meta.servedTurn,
                    meta.notice,
                    meta.keepaliveMs,
                    meta.streamIdleMs,
                ),
                withheldHeaders = meta.withheldHeaders,
            )
            if (counting) route.provider.countTokens(call, turn) else route.provider.serve(call, turn)
        }
    }
}

private const val STARTING = "this server's door opens once its providers are up"
