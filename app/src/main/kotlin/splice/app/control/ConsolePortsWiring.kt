// Console route ports and their app-side adapters, assembled after the control server is constructed.
package splice.app.control

import splice.app.AddWiring
import splice.app.DoctorWiring
import splice.app.LifecycleWiring
import splice.app.auth.ConsoleAccountsImpl
import splice.app.console.DrainingRestartAdapter
import splice.app.daemon.BootedTopology
import splice.core.config.KeyStore
import splice.core.config.KeyStorePath
import splice.core.model.HeadDiscoveredModels
import splice.core.topology.TopologyParse
import splice.core.topology.TopologyWriter
import splice.core.util.EnvReader
import splice.core.util.LogSink
import splice.diagnostics.doctor.DoctorReport
import splice.lifecycle.upgrade.AutoUpdateSettings
import splice.lifecycle.upgrade.ConsoleUpgradeStatus
import splice.lifecycle.upgrade.UpgradeChecks
import splice.lifecycle.upgrade.UpgradeStatus
import splice.share.ShareListener
import splice.share.remote.RemoteLinks
import splice.topology.TopologyLoader
import java.nio.file.Path

internal object ConsolePortsWiring {
    internal fun wire(
        srv: ControlServer,
        topology: BootedTopology,
        discovered: HeadDiscoveredModels,
        configBackups: Path,
        log: LogSink,
    ) {
        // V4-127: the console's three read ports, assigned after construction because a constructor
        // parameter would widen ControlServer past the width ratchet — so the compiler cannot check
        // any of these lines, and deleting one does not break the build. Each route then answers its
        // NAMED 5xx, and the upgrade one is the worst of them: an unwired port there reads as a
        // measured payload saying nothing is newer, which tells an operator they are up to date when
        // nobody has ever looked. The deletion pins in ConsoleWiringPinTest are what make that a red
        // instead of a quiet lie.
        srv.ports.declaredHeads = topology.declaredHeads
        srv.ports.doctor = DoctorReport(DoctorWiring::consoleJson)
        srv.ports.doctorFixes = DoctorWiring.fixes()
        val upgradeChecks = UpgradeChecks(
            EnvReader(System::getenv),
            settings = topology.path?.let { LifecycleWiring.updateSettings(it, log) } ?: AutoUpdateSettings { null },
        )
        srv.ports.lifecycle.upgradeChecks = upgradeChecks
        val upgradeStatus = ConsoleUpgradeStatus(EnvReader(System::getenv), upgradeChecks)
        srv.ports.lifecycle.upgrade = UpgradeStatus(upgradeStatus::json)
        // V4-137: the draining restart's supervision probe. Unlike the three above, leaving this one
        // unassigned is SAFE BY CONSTRUCTION — ConsolePorts.supervised is null until set and the
        // route refuses on null, so an unwired port declines to drain rather than draining a daemon
        // nothing would restart. It is assigned here anyway because the refusal is not the answer we
        // want on a host where systemd does run the daemon, and pinned for the same reason the others
        // are: the compiler cannot see this line either.
        srv.ports.lifecycle.supervised = DrainingRestartAdapter()

        // V4-128: the writer is the ONLY seam that edits splice.toml, and it is built here because
        // this is the one place that knows the booted file's path. A null path is a daemon booted
        // without a config file: the route declines rather than writing a file nobody asked for.
        // V4-284: its backups go to splice's own [configBackups], never beside the file.
        srv.ports.topology = topology.path?.let {
            TopologyWriter(it, configBackups, TopologyParse(TopologyLoader::parse), discovered = discovered)
        }

        // V4-132: the login/remove/relabel machinery — :daemon-control depends on :core only, so this is
        // the port's :app-side implementation, assigned here like every port above it. Unassigned,
        // the four routes behind it answer a named 503 rather than a payload reading as "no
        // accounts".
        srv.ports.accounts = ConsoleAccountsImpl()

        // LAN share: the Sharing page reads and turns the listener the daemon built; unassigned, its rows answer 503.
        srv.ports.sharing = topology.sharing?.let { SharingParts(it, topology.remotes, booted = topology.path) }

        // V4-220 item 3: the SAME keys.toml every api-key head reads (ApiKeyAuthProvider's default is
        // KeyStorePath.defaultPath() over this process's environment), so a console PUT lands in the
        // file those heads read on their next request. Its unreadable-store line goes to the daemon log.
        srv.ports.keys = KeyStore(KeyStorePath.defaultPath())

        // V4-220 item 3: `splice add` over /api/add. Unassigned, every add route answers a named 503.
        srv.ports.add = AddWiring.console(log)

        // V4-220 item 4: `splice upgrade` over POST /api/upgrade, run out of process. Unassigned, it and
        // GET /api/upgrade/run answer a named 503.
        val upgradeRuns = LifecycleWiring.consoleUpgrades()
        srv.ports.lifecycle.upgradeRuns = upgradeRuns
        // Auto-update (Oct 13): the same runs, started by the daemon itself while splice.toml says auto_update = true.
        topology.path?.let { LifecycleWiring.autoUpdate(upgradeRuns, it, log).start() }
    }
}

/** This machine's share listener, and the machines it joined (null where no daemon built them): what the Sharing page's
 *  rows read and turn (LAN share, milestone 4), a port of ConsolePorts assigned after the server is built. [env] is the
 *  daemon's: where the state folder is, and splice.toml where the daemon booted none. [booted] is the splice.toml the
 *  daemon booted, whatever file [env] names (round 14 of PR #419's review): the one its links and switches read. */
internal data class SharingParts(
    val listener: ShareListener,
    val remotes: RemoteLinks?,
    val env: EnvReader = EnvReader(System::getenv),
    val booted: Path? = null,
) {
    /** [env] naming [booted] as splice.toml: where every row reads and writes the file and the state folder. */
    val daemonEnv: EnvReader = booted?.let { path -> EnvReader { if (it == CONFIG_VAR) "$path" else env(it) } } ?: env
}

// why: the variable splice.toml's path is read from (TopologyLoader.configPath), set to the file the daemon booted.
private const val CONFIG_VAR = "SPLICE_CONFIG"
