// NEW: LAN share, phase 1 (docs/specs/lan-share.md). What the daemon calls once its heads and control plane
// are up: nothing when `[share]` is off, else the share listener over the heads that started. A port that
// will not bind is logged and sharing stays off; the daemon and every local session carry on.
package splice.share

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

public class ShareStart(
    private val topology: Topology,
    private val statePaths: StatePaths,
    private val mgmtKey: MgmtKey,
    /** Each offered head's live config: the relay reads a client's body under that head's own limits. */
    private val config: ConfigService,
    private val log: LogSink,
) {
    /** The running listener, or null when `[share]` is off or its port would not open. */
    public fun start(running: SharedCatalogs): ShareServer? {
        val share = topology.share
        if (!share.enabled) return null
        val host = ShareHostName()
        val tls = certificate(host, share.hostnames) ?: return null
        val io = ProcessDispatchers().io()
        val server = ShareServer(
            ShareEndpoint(share.port, tls),
            ShareHeads(topology, running, host.short(), tls.fingerprint),
            ShareClients(ShareState(statePaths).clientsFile),
            ShareNetwork(host.full(), share.hostnames),
            ShareRelay(TurnKey(mgmtKey), config, io, log),
            io,
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

    /** The listener's certificate, minted on the first start; null, said, when the file holds none it can serve. A
     *  new one is never minted over it: every joined machine pinned the old one. */
    private fun certificate(host: ShareHostName, hostnames: List<String>): ShareTls? {
        val file = ShareState(statePaths).certificateFile
        val names = (listOf(host.short(), host.full(), "${host.short()}.local", "${host.short()}.lan") + hostnames)
            .distinct()
        return Cancellables.runCatchingCancellable { ShareCertificate(file).load(names) }.fold(
            onSuccess = { it },
            onFailure = { failure ->
                val why = SafeFailureText.render(failure)
                val fix = "move it away to mint a new one, and every joined machine then runs `splice remote add` again"
                log("[share] not sharing: $file is not a certificate this machine can serve ($why); $fix\n")
                null
            },
        )
    }
}

/** Where sharing keeps its state, for the daemon and the `splice share` and `splice remote` verbs alike: the machines
 *  that joined this one, this machine's own tokens on the machines it joined, and the listener's certificate. */
public class ShareState(statePaths: StatePaths) {
    public val clientsFile: Path = statePaths.stateDir.resolve("share").resolve("clients.json")
    public val remotesFile: Path = statePaths.stateDir.resolve("share").resolve("remotes.json")
    /** The listener's key and certificate (ShareCertificate). */
    public val certificateFile: Path = statePaths.stateDir.resolve("share").resolve("tls.pem")
}

/** This machine's name: [full] as the OS reports it, [short] up to the first dot, as clients are told it. */
public class ShareHostName {
    private val name: String by lazy {
        Cancellables.runCatchingCancellable { InetAddress.getLocalHost().hostName }
            .fold(onSuccess = { it.ifBlank { UNNAMED } }, onFailure = { UNNAMED })
    }

    public fun full(): String = name

    public fun short(): String = name.substringBefore('.')
}

// why: the name a machine goes by when the OS reports none, so a join and a listing still have something to show.
private const val UNNAMED = "splice"
