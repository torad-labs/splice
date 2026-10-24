// NEW: LAN share (docs/specs/lan-share.md, Marcos's ruling of 2026-10-24). The daemon's two parts of it: the launch of a
// command another machine serves (`[remote.*]`), which the launch service asks when no head here has the name, and the
// listener that offers this machine's heads to its other machines (`[share]`), opened once the heads and the control
// plane are up and closed first at stop. A listener that can't open (a port another program holds) is said in the log
// and leaves the rest of the daemon serving.
package splice.app.share

import splice.accounts.pool.HeadAccountPinSource
import splice.app.control.ManagedHead
import splice.client.ClaudePolicy
import splice.core.config.ConfigService
import splice.core.config.TurnKey
import splice.core.topology.Topology
import splice.core.util.Cancellables
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import splice.launch.LaunchSpec
import splice.launch.RemoteCommands
import splice.share.RemoteLaunches
import splice.share.RemoteTrees
import splice.share.ShareListener
import splice.share.SharedHead
import splice.share.SharedHeads
import splice.upstream.codemode.ProcessDispatchers
import java.net.InetAddress

internal class DaemonShare(
    private val topology: Topology,
    private val config: ConfigService,
    private val turnKey: TurnKey,
    private val log: LogSink,
) {
    @Volatile
    private var listener: ShareListener? = null

    /** The commands this machine runs on another's heads, launched from [trees] under [policy]. */
    fun remotes(trees: RemoteTrees, policy: ClaudePolicy): RemoteCommands =
        RemoteLaunches(topology.remote, trees, policy, ProcessDispatchers().io())

    /** Opens the listener when `[share]` turns it on, offering [heads] and [controlPort]'s statusline. */
    fun open(heads: Map<String, ManagedHead>, controlPort: Int) {
        if (!topology.share.enabled) return
        val offered = ManagedSharedHeads(heads, topology, config, machineName())
        val opened = ShareListener(topology.share, offered, turnKey, controlPort, ProcessDispatchers().io(), log)
        Cancellables.runCatchingBestEffort { opened.start() }
            .onSuccess { listener = opened }
            .onFailure { failure ->
                val why = SafeFailureText.render(failure)
                log("[share] not sharing: the listener on :${topology.share.port} did not open: $why\n")
            }
    }

    fun close() {
        listener?.stop()
        listener = null
    }

    /** This machine's name as the OS gives it, which another machine's address names and its Host carries. */
    private fun machineName(): String = Cancellables.runCatchingCancellable { InetAddress.getLocalHost().hostName }
        .getOrElse { failure ->
            log("[share] this machine's name could not be read (${SafeFailureText.render(failure)})\n")
            LOCALHOST
        }
}

/** The heads this machine offers: every head with a launch that holds its own credentials. A head that forwards the
 *  caller's own Claude login has nothing to offer a machine that holds none. */
internal class ManagedSharedHeads(
    private val heads: Map<String, ManagedHead>,
    private val topology: Topology,
    private val config: ConfigService,
    override val machine: String,
) : SharedHeads {
    override fun offered(): List<SharedHead> = heads.mapNotNull { (key, head) ->
        val spec = head.launchSpec?.takeUnless { it.gateway.forwardClientAuth } ?: return@mapNotNull null
        Offered(key, topology.heads[key]?.claude?.command ?: key, head, spec)
    }

    private inner class Offered(
        override val key: String,
        override val command: String,
        private val head: ManagedHead,
        private val spec: LaunchSpec,
    ) : SharedHead {
        override val port: Int get() = spec.gateway.port

        override fun running(): Boolean = head.head.healthSnapshot().running

        override fun launch(): LaunchSpec = head.statusline.catalog?.let(spec::withWindows) ?: spec

        override fun logins(): List<String> =
            head.authSurface.accountPool?.view(null)?.accounts?.map { it.label }.orEmpty()

        override fun pin(login: String, session: String): Boolean =
            (head.authSurface.accountPool as? HeadAccountPinSource)?.pin(login, session) ?: false

        override fun maxRequestBytes(): Int = config.getConfig(key).maxRequestBytes

        override fun requestReadTimeoutMs(): Long = config.getConfig(key).requestReadTimeoutMs
    }
}

// What this machine is called when the OS can't say: an address naming it is still answered by IP.
private const val LOCALHOST = "localhost"
