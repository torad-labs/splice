// PORT-OF: PassthroughStreamTranslator.kt @ 71a203c — invariants unchanged: verbatim move, zero
// behavioural coupling to the translator (it is only a ctor parameter).
package splice.dialect.passthrough

import splice.core.util.DaemonLog
import splice.core.util.LogSink
import splice.spi.ClientGone
import splice.spi.WatchdogProbe

public data class PassthroughTurnContext(
    val clientGone: ClientGone,
    val watchdogFired: WatchdogProbe,
    val idleCapMs: Long,
    val totalCapMs: Long,
    /** Daemon log sink (Main.persistentLogger): writes BOTH stderr and daemon.log, which is what
     *  /mgmt/logs tails (wall kt-no-println). The translator's only anomaly channel — it has no
     *  per-turn perf handle (Provider.streamTranslator threads none). Uninstalled, DaemonLog is a
     *  no-op — never a silent stderr write — so tests need not thread it; once Main installs the
     *  process sink this same reference starts writing to it, so no call site (including
     *  PassthroughProvider) needs to pass it explicitly. */
    val log: LogSink = LogSink(DaemonLog::write),
)
