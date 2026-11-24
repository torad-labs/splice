// NEW: head-catalog (Nov 19, 2026; Marcos: one list, the Splice gateway's, in every session). What a head's command
// changes in its launch to start Claude Code on the Splice gateway, in Claude Code's gateway mode (GatewayMode), as
// `claude` does: the gateway's address and key in place of the head's own route and bearer, the head's own model as
// the gateway lists it, the one window the gateway reports every count as a share of, and no list of the head's own.
// The launch is recorded as the head's before the session starts, since no head's port answers the gateway's address
// (ProcessEnvironment.route). Split from LaunchService, which builds the rest of a head's launch as before.
package splice.launch.recipe

import splice.client.SessionHelpers
import splice.client.desktop.FIRST_PARTY_ENV
import splice.client.desktop.GATEWAY_BASE_URL
import splice.client.desktop.GATEWAY_TOKEN
import splice.client.desktop.GatewayMode
import splice.core.config.StatePaths
import splice.core.process.LaunchDeclaration
import splice.core.process.LaunchOwners
import splice.core.util.Cancellables
import splice.launch.GatewayBearer
import splice.launch.LaunchSpec

internal class GatewayLaunch(private val owners: LaunchOwners = LaunchOwners(StatePaths().stateDir)) {
    private val mode = GatewayMode()

    /** What a launch on the gateway takes out of the environment it inherits: every way past it, every model list but
     *  its own, and the compaction window a head plants. */
    val unset: List<String> = mode.unset + COMPACT_WINDOW

    /** [spec] with its session holding [at]'s key, the gateway's, in place of the head's own bearer: no helper of the
     *  head's own turn key beside it (Claude Code 2.1.329 warns "Both ANTHROPIC_AUTH_TOKEN and apiKeyHelper set").
     *  [spec] as it is off the gateway. */
    fun keyed(spec: LaunchSpec, at: GatewayAt?): LaunchSpec =
        at?.let { spec.copy(gateway = spec.gateway.copy(bearer = GatewayBearer.Planted(it.key))) } ?: spec

    /** The helpers [keyed]'s head folder keeps: those of [keyed], with the key helper of [own], the head's spec before
     *  [keyed] took its bearer. Upgrade (Nov 24, 2026): a session an older splice launched holds no bearer and reads
     *  its turn key through that folder's helper at every start, so a launch on the gateway that took the helper out of
     *  the folder left every such session "Not logged in". The session on the gateway sets it aside ([overlay]). */
    fun folderHelpers(keyed: LaunchSpec, own: LaunchSpec): SessionHelpers =
        keyed.helpers.copy(apiKey = own.gateway.keyHelper)

    /** The --settings overlay of [own]'s session on the gateway [at] when its folder keeps a key helper
     *  ([folderHelpers]): the helper set aside, so the session holds the gateway's key alone. Null off the gateway and
     *  for a head with no helper. */
    fun overlay(own: LaunchSpec, at: GatewayAt?): String? =
        mode.helperSetAside.takeIf { at != null && own.gateway.keyHelper != null }

    /** The tiers [spec]'s launch plants: none on the gateway [at], which runs a tier on the session's head; a command
     *  another machine serves still plants its own. */
    fun slots(spec: LaunchSpec, at: GatewayAt?): List<Pair<String, String>> =
        if (at == null) LaunchAliasSlots.of(spec) else emptyList()

    /** What [spec]'s head sets on the gateway [at]: [own], everything else its launch carries (with no tiers), less its
     *  own address, bearer, model and windows, and gateway mode with the head's model and the head's own window, the
     *  one its turns name to the gateway (LaunchModelEnvironment.windowHeader), as 0.6.4 launched it; the gateway's
     *  for a head that has none. */
    fun env(own: Map<String, String>, spec: LaunchSpec, at: GatewayAt): Map<String, String> {
        val model = at.model ?: spec.models.pinnedSelector
        val window = spec.models.contextWindow.takeIf { it > 0L } ?: at.window
        return own - OWN_ROUTE + mode.env(at.base, at.key, model) + (WINDOW to window.toString())
    }

    /** The session the launcher [launch] becomes recorded as [head]'s on the gateway [at], before it starts, so
     *  `splice sessions` names its head from its first moment; the shim's own record, written just before it execs
     *  with its terminal, takes its place. A launcher that sent no pid, or whose record the disk refuses, is left to
     *  the shim's record and the session's turns. */
    fun record(launch: String?, head: String, at: GatewayAt?) {
        val pid = launch?.toLongOrNull()
        if (pid == null || at == null) return
        val declared = LaunchDeclaration(head, at.base, SESSION_KIND, ORIGIN)
        Cancellables.runCatchingCancellable { owners.write(pid, declared) }
    }
}

/** A launch record's kind and origin for a session a launch starts (LaunchOwners), as the shim writes them. */
private const val SESSION_KIND = "session"
private const val ORIGIN = "other"

/** The window a session holds, and the compaction window a head plants beside it, which a session on the gateway
 *  does not: every count the gateway reports is a share of the one window [GatewayAt] names. */
private const val WINDOW = "CLAUDE_CODE_MAX_CONTEXT_TOKENS"
private const val COMPACT_WINDOW = "CLAUDE_CODE_AUTO_COMPACT_WINDOW"

/** What a head's launch sets for its own route, which a session on the gateway takes from the gateway instead: its
 *  address, a bearer, the model, the windows, and the word that its address is Anthropic's own. */
private val OWN_ROUTE =
    setOf(GATEWAY_BASE_URL, GATEWAY_TOKEN, "ANTHROPIC_MODEL", WINDOW, COMPACT_WINDOW, FIRST_PARTY_ENV)
