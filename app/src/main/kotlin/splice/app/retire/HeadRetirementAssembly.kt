// NEW: head-catalog (gw-retire). What `splice claude retire` and `restore` are built from is the control server's parts,
// as HeadRemovalAssembly does for a removal: the heads as their launches are, the session registry, the install's bin
// dir, and the lane `claude` is on behind the daemon's Splice gateway, so the retirement itself names none of them.
package splice.app.retire

import splice.app.control.ClaudeLink
import splice.app.control.ConsolePorts
import splice.app.control.ControlRuntime
import splice.app.control.DaemonWrap
import splice.app.control.ManagedHead
import splice.app.desktop.lane.ClaudeLane
import splice.app.desktop.setup.DesktopAct
import splice.app.head.HeadRemovalFacts
import splice.app.head.RegisteredSessions
import splice.app.head.WrapperBinSource
import splice.app.head.WrapperLinks
import splice.core.config.ConfigService
import splice.core.config.UserHome
import splice.core.topology.CLAUDE_COMMAND
import splice.core.util.LogSink

internal class HeadRetirementAssembly(
    private val config: ConfigService,
    private val ports: ConsolePorts,
    private val runtime: ControlRuntime,
    private val heads: Map<String, ManagedHead>,
    private val log: LogSink,
) {
    fun build(): HeadRetirement = HeadRetirement(
        RetiringHeads { heads.values.mapNotNull(::retiring) },
        config.statePaths,
        HeadRemovalFacts(config.statePaths, heads, RegisteredSessions { runtime.sessions }),
        WrapperLinks(WrapperBinSource { ports.wrapperBin }, log),
        GatewayLane(ports, ClaudeLink(DaemonWrap { runtime.launchService?.wrap })),
        UserHome.dir(),
    )

    /** [managed] as retiring reads it, or null for a head with no command to launch. The head `claude` runs on is
     *  wrapped over the person's own ~/.claude, so it has no folder of its own to empty. */
    private fun retiring(managed: ManagedHead): RetiringHead? {
        val spec = managed.launchSpec ?: return null
        val command = managed.head.label
        return RetiringHead(
            key = managed.head.key,
            command = command,
            folder = spec.trees.own.takeIf { command != CLAUDE_COMMAND },
            selector = spec.models.pinnedSelector,
            gateway = spec.startsOnGateway,
        )
    }
}

/** The lane `claude` is on, behind the daemon's Splice gateway, read at each use: the gateway opens once the heads are
 *  up. */
private class GatewayLane(private val ports: ConsolePorts, private val link: ClaudeLink) : RetiringLane {
    private val claude: ClaudeLane? get() = ports.desktop?.opened?.claude

    override fun lane(): String? = claude?.lane()

    override suspend fun toGateway(): String = when (val act = claude?.toDoor(null)) {
        is DesktopAct.Done -> link.linked("`claude` is on the Splice gateway now, as `splice claude door` puts it")
        is DesktopAct.Refused -> "`claude` stays on its head: ${act.why}"
        null -> "`claude` stays on its head: the Splice gateway is not open yet; `splice claude door` moves it"
    }

    override suspend fun toHead(): String = when (val act = claude?.toHead()) {
        is DesktopAct.Done -> "`claude` is back on its head, as it was when its head was retired"
        is DesktopAct.Refused -> "`claude` stays on the Splice gateway: ${act.why}"
        null -> "`claude` stays where it is: the Splice gateway is not open yet; `splice claude head` moves it"
    }

    override suspend fun selectors(key: String): List<String> =
        claude?.lineup()?.picker?.filter { it.provider == key }?.map { it.model }.orEmpty()
}
