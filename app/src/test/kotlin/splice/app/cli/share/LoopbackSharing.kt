// NEW: LAN share, round 15 of PR #419's review. A machine sharing on loopback for the `splice remote` tests: a real
// share listener over a state folder of its own, found as `splice remote add 127.0.0.1:<port>` finds it. It goes by
// this host's name, as every listener in this JVM does, so two of them are two machines with one name. Lifted out of
// RemoteImportFileTest, which had it as a function, once a second test and a second machine needed it.
package splice.app.cli.share

import kotlinx.coroutines.runBlocking
import splice.core.config.ConfigService
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import splice.core.testing.TestPorts
import splice.core.topology.ShareConfig
import splice.core.topology.Topology
import splice.core.util.EnvReader
import splice.share.MachinesJoined
import splice.share.ShareListener
import splice.share.ShareStart
import splice.share.SharedCatalogs
import splice.share.SharedQuotas
import splice.share.remote.RemoteFound
import splice.share.remote.RemoteHttp
import splice.share.remote.RemoteTrust
import java.nio.file.Path
import java.util.concurrent.TimeUnit

// why: the sharing machine is on loopback in this JVM; five seconds covers a loaded machine minting its certificate.
private const val INFO_WAIT_S = 5L

/** A machine sharing on loopback from [folder] under [tmp]. */
internal class LoopbackSharing(tmp: Path, folder: String) : AutoCloseable {
    private val vars = mapOf("SPLICE_STATE_DIR" to tmp.resolve(folder).toString(), "HOME" to tmp.toString())
    private val paths = StatePaths(envReader = EnvReader { vars[it] })
    private val listener = ShareListener(
        ShareStart(Topology(), paths, MgmtKey(paths), ConfigService(paths)) { },
        SharedCatalogs { null },
        SharedQuotas { null },
        paths,
        MachinesJoined { emptySet() },
        { },
    )
    val port: Int = TestPorts.reserve()

    /** The machine as a scan or `splice remote add` finds it: its address, its port and what it offers. */
    val found: RemoteFound

    init {
        check(runBlocking { listener.follow(ShareConfig(enabled = true, port = port)) }.listening) { "it shares" }
        val info = RemoteHttp(RemoteTrust(null, "a sharing machine")).info("127.0.0.1", port)
            .get(INFO_WAIT_S, TimeUnit.SECONDS)
        found = RemoteFound("127.0.0.1", port, checkNotNull(info) { "the sharing machine answered" })
    }

    override fun close() {
        runBlocking { listener.stop() }
    }
}
