// NEW: gateway-no-heads (.git/campaign/gateway-no-heads.json). What the keyed team listener asks of the gateway to
// serve a team's turns on its providers, with no head: the gateway reads a request, resolves the model it names (an
// alias, an old head prefix, a readable name) to the provider that serves it, and serves the turn on that provider's
// own serving. The listener keeps what is the server's: the team key, the person's caps, the ledger, the minimum
// client and the person's allowlist, applied to the provider the gateway selected and never to the selector a client
// wrote.
package splice.server

import io.ktor.server.application.ApplicationCall
import splice.share.RefusalVoice

/** The gateway the team listener serves a team's turns on. */
public interface TeamGateway {
    /** [call]'s turn (or, with [counting], its token count) read and routed to the provider its model names, among the
     *  providers [serves] says this server serves; null once [call] has been answered: a body past what a provider
     *  takes, one that is no JSON, or a model no served provider runs. Nothing is served until
     *  [RoutedTeamTurn.serve]. */
    public suspend fun routed(
        call: ApplicationCall,
        voice: RefusalVoice,
        counting: Boolean,
        serves: ProviderFilter,
    ): RoutedTeamTurn?

    /** The models a person may pick, as a gateway client's `GET /v1/models` reads them: those of the providers
     *  [serves] says this server serves and this person may use. */
    public suspend fun models(call: ApplicationCall, voice: RefusalVoice, serves: ProviderFilter)
}

/** Whether a provider, by its key and every name an operator may call it (its key, and the command or key of a head
 *  that runs it), is one the asker means. */
public fun interface ProviderFilter {
    public operator fun invoke(provider: String, names: Set<String>): Boolean
}

/** A turn the gateway has read and routed, not yet served. */
public interface RoutedTeamTurn {
    /** The provider the model resolved to, whatever the client wrote. */
    public val provider: String

    /** What an operator calls it in `[server] commands` and in a person's allowlist. */
    public val names: Set<String>

    /** The turn served on [provider] with [meta], which the listener authored: the provider reads these from here and
     *  never from a request header. */
    public suspend fun serve(meta: TeamTurnMeta)
}

/** What the authenticated listener says of a turn, in place of the headers a head's relay used to set: the upstream its
 *  person prefers, the id its ledger row names it by, and what an older laptop is told instead of an answer. */
public data class TeamTurnMeta(
    val preferredUpstream: String? = null,
    val servedTurn: String? = null,
    val notice: String? = null,
    /** How often the listener asks a streamed turn's client be sent a keepalive while its answer is silent, in
     *  milliseconds: `[server] keepalive_seconds`. Null asks for none beyond the native disconnect probe. */
    val keepaliveMs: Long? = null,
    /** How long a streamed turn's answer may fall silent once its client has seen some of it before it is ended, in
     *  milliseconds: `[server] stream_idle_seconds` (#791). Null sets no such deadline. */
    val streamIdleMs: Long? = null,
)
