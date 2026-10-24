// NEW: LAN share, phase 1 (docs/specs/lan-share.md). Opens the share listener a `[share]` table asks for, over the
// heads that started: nothing when it is off. A port that will not bind, or a certificate that cannot be served, is
// logged and answered as why; sharing stays off and the daemon and every local session carry on. Since milestone 4
// ShareListener calls it for each version of the table, so the listener follows the file without a daemon restart.
package splice.share

import splice.core.config.ConfigService
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import splice.core.config.TurnKey
import splice.core.topology.ShareConfig
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
    /** The listener [share] asks for, with [clients] as its book of joined machines, or why there is none. [running]
     *  and [quotas] read the daemon's heads as they are at each request; [sessions] hosts a joined machine's sessions
     *  and holds what sessions here write to them. Blocking: it binds a port. */
    internal fun open(
        share: ShareConfig,
        clients: ShareClients,
        running: SharedCatalogs,
        quotas: SharedQuotas,
        sessions: ShareSessionParts,
    ): ShareOpen {
        if (!share.enabled) return ShareOpen.Off
        val host = ShareHostName()
        val tls = certificate(host, share.hostnames).getOrElse { failure ->
            val file = ShareState(statePaths).certificateFile
            val fix = "move it away to mint a new one, and every joined machine then runs `splice remote add` again"
            val why = "$file is not a certificate this machine can serve (${SafeFailureText.render(failure)}); $fix"
            log("[share] not sharing: $why\n")
            return ShareOpen.Refused(why)
        }
        val io = ProcessDispatchers().io()
        val relay = ShareRelay(TurnKey(mgmtKey), config, io, log)
        val server = ShareServer(
            ShareEndpoint(share.port, tls, ShareNetwork(host.full(), share.hostnames, HostPrefixes(log))),
            ShareHeads(topology.copy(share = share), running, host.short(), tls.fingerprint, quotas),
            clients,
            relay,
            ShareSessionHost(sessions, relay, ShareErrors(), io),
            io,
            log,
        )
        return Cancellables.runCatchingCancellable { server.also(ShareServer::start) }.fold(
            onSuccess = { ShareOpen.Listening(it) },
            onFailure = { failure ->
                val why = "port ${share.port} did not open: ${SafeFailureText.render(failure)}"
                log("[share] not sharing: $why\n")
                ShareOpen.Refused(why)
            },
        )
    }

    /** The listener's certificate, minted on the first start, or the failure of a file that holds none it can serve.
     *  A new one is never minted over it: every joined machine pinned the old one. */
    private fun certificate(host: ShareHostName, hostnames: List<String>): Result<ShareTls> {
        val file = ShareState(statePaths).certificateFile
        val names = (listOf(host.short(), host.full(), "${host.short()}.local", "${host.short()}.lan") + hostnames)
            .distinct()
        return Cancellables.runCatchingCancellable { ShareCertificate(file).load(names) }
    }
}

/** What opening the listener came to: [Off] when `[share]` is off, else [Listening] or [Refused] with why, in words. */
internal sealed class ShareOpen {
    data object Off : ShareOpen()

    data class Listening(val server: ShareServer) : ShareOpen()

    data class Refused(val why: String) : ShareOpen()
}

/** Where sharing keeps its state, for the daemon and the `splice share` and `splice remote` verbs alike: the machines
 *  that joined this one, this machine's own tokens on the machines it joined, the listener's certificate, and the
 *  mirrors' log. */
public class ShareState(statePaths: StatePaths) {
    private val dir: Path = statePaths.stateDir.resolve("share")

    public val clientsFile: Path = dir.resolve("clients.json")
    public val remotesFile: Path = dir.resolve("remotes.json")

    /** The listener's key and certificate (ShareCertificate). */
    public val certificateFile: Path = dir.resolve("tls.pem")

    /** What the mirrors of other machines' sessions saw: counts and errors, never a message's text. */
    public val mirrorsLog: Path = dir.resolve("mirrors.log")
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
