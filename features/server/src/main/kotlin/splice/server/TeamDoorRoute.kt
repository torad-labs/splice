// NEW: gateway-no-heads. The team's door: `/door/v1/{messages, messages/count_tokens, models}` on the keyed listener,
// the same door the other callers have, with the team key in front of it. A turn is read and routed by the gateway to
// the provider its model names, an alias or an old head prefix included; this route then applies what is the server's
// to that provider, never to the selector the client wrote: the person's allowlist (a 403 naming the provider), their
// caps and budget, the ledger row, and the minimum client; and hands the turn to the provider's own serving with the
// upstream the person prefers and the id the ledger names it by, said as metadata this listener authors. No head is
// read, and none is relayed to. The `/<command>/...` routes of the heads that exist keep working beside it.
package splice.server

import io.ktor.http.HttpMethod
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.httpMethod
import splice.core.topology.ServerConfig
import splice.core.wire.HttpStatus
import splice.server.client.ClientMinimum
import splice.server.ledger.ROUTE_MESSAGES
import splice.share.HEAD_API_ROOT
import splice.share.RefusalVoice

internal class TeamDoorRoute(
    private val server: ServerConfig,
    private val parts: ServerParts,
    private val minimum: ClientMinimum,
    private val admission: TurnAdmission,
    private val voice: RefusalVoice,
    private val bound: ProviderBound,
) {
    private val turnPath = listOf(HEAD_API_ROOT, "messages")
    private val countPath = turnPath + "count_tokens"
    private val modelsPath = listOf(HEAD_API_ROOT, "models")

    /** `[server] keepalive_seconds` as the cadence a streamed turn's client is pinged at; none when it is 0. */
    private val keepaliveMs = server.keepaliveSeconds.takeIf { it > 0 }?.let { it * KEEPALIVE_MS_PER_SECOND }

    /** [rest], the path after `/door`, asked by [key]'s person. */
    suspend fun answer(call: ApplicationCall, key: ServerKey, rest: List<String>) {
        val gateway = parts.carriers.gateway ?: return voice.refuse(call, HttpStatus.NOT_FOUND, TEAM_DOOR_SHUT)
        val method = call.request.httpMethod
        when {
            method == HttpMethod.Post && rest == turnPath -> turn(call, key, gateway)
            method == HttpMethod.Post && rest == countPath -> counted(call, key, gateway)
            method == HttpMethod.Get && rest == modelsPath -> listed(call, key, gateway)
            else -> voice.refuse(call, HttpStatus.NOT_FOUND, "no such path on this server's door")
        }
    }

    /** A turn: one of the person's slots, or refused; an older laptop's is answered in the conversation, or refused
     *  with `[server.client] outdated_status`, and takes none (F9). */
    private suspend fun turn(call: ApplicationCall, key: ServerKey, gateway: TeamGateway) {
        val routed = gateway.routed(call, voice, counting = false, served()) ?: return
        val notice = minimum.told(call, key.person)
        val withheld = bound.withheld(call)
        if (notice != null) {
            if (minimum.refused(call, voice, notice)) return
            return routed.serve(TeamTurnMeta(notice = notice, withheldHeaders = withheld))
        }
        val provider = ProviderCommand(routed.provider, routed.names)
        admission.served(call, key, provider, ROUTE_MESSAGES) { turn ->
            val preferred = parts.people.of(key.person).preferred
            val named = parts.books.turns.named(turn).second
            routed.serve(TeamTurnMeta(preferred, named, keepaliveMs = keepaliveMs, withheldHeaders = withheld))
        }
    }

    /** A token count: no slot, but the person's allowlist holds as it does for a turn. */
    private suspend fun counted(call: ApplicationCall, key: ServerKey, gateway: TeamGateway) {
        val routed = gateway.routed(call, voice, counting = true, served()) ?: return
        val refusal = parts.limits.access.refusal(key.person, ProviderCommand(routed.provider, routed.names))
        if (refusal != null) return voice.refuse(call, HttpStatus.FORBIDDEN, refusal)
        routed.serve(TeamTurnMeta(withheldHeaders = bound.withheld(call)))
    }

    /** The models the person may pick: those of the providers this server serves and their allowlist lets them use. */
    private suspend fun listed(call: ApplicationCall, key: ServerKey, gateway: TeamGateway) {
        val served = served()
        gateway.models(call, voice) { provider, names ->
            served(provider, names) &&
                parts.limits.access.refusal(key.person, ProviderCommand(provider, names)) == null
        }
    }

    /** The providers this server serves: those `[server] commands` names, by key or by a head that runs one. */
    private fun served(): ProviderFilter = ProviderFilter { _, names -> names.any { it in server.commands } }
}

// why: `[server] keepalive_seconds` is written in seconds and a turn's keepalive is counted in milliseconds.
private const val KEEPALIVE_MS_PER_SECOND = 1_000L

private const val TEAM_DOOR_SHUT = "this server's door is not open: no provider is up to serve it"
