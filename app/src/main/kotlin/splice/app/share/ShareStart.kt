// NEW: LAN share, phase 1 (docs/specs/lan-share.md). What the daemon calls once its heads and control plane
// are up: nothing when `[share]` is off, else the share listener over the heads that started. A port that
// will not bind is logged and sharing stays off; the daemon and every local session carry on.
package splice.app.share

import splice.app.control.ManagedHead
import splice.core.config.ConfigService
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import splice.core.config.TurnKey
import splice.core.topology.Topology
import splice.core.util.Cancellables
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import splice.upstream.codemode.ProcessDispatchers
import java.net.InetAddress
import java.nio.file.Path

internal class ShareStart(
    private val topology: Topology,
    private val statePaths: StatePaths,
    private val mgmtKey: MgmtKey,
    /** Each offered head's live config: the relay reads a client's body under that head's own limits. */
    private val config: ConfigService,
    private val log: LogSink,
) {
    fun start(running: Map<String, ManagedHead>): ShareServer? {
        val share = topology.share
        if (!share.enabled) return null
        val host = ShareHostName()
        val server = ShareServer(
            share.port,
            ShareHeads(topology, running, host.short()),
            ShareClients(ShareState(statePaths).clientsFile),
            ShareNetwork(host.full(), share.hostnames),
            ShareRelay(TurnKey(mgmtKey), config, ProcessDispatchers().io(), log),
            log,
        )
        return Cancellables.runCatchingCancellable { server.also(ShareServer::start) }.fold(
            onSuccess = { it },
            onFailure = { failure ->
                log("[share] not sharing: :${share.port} did not open: ${SafeFailureText.render(failure)}\n")
                null
            },
        )
    }
}

/** Where sharing keeps its state, for the daemon and the `splice share` verb alike. */
internal class ShareState(statePaths: StatePaths) {
    val clientsFile: Path = statePaths.stateDir.resolve("share").resolve("clients.json")
}

/** This machine's name: [full] as the OS reports it, [short] up to the first dot, as clients are told it. */
internal class ShareHostName {
    private val name: String by lazy {
        Cancellables.runCatchingCancellable { InetAddress.getLocalHost().hostName }
            .fold(onSuccess = { it.ifBlank { UNNAMED } }, onFailure = { UNNAMED })
    }

    fun full(): String = name

    fun short(): String = name.substringBefore('.')
}

private const val UNNAMED = "splice"
