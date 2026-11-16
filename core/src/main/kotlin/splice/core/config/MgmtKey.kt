// PORT-OF: server/src/mgmt/api.mjs ensureMgmtKey @ pre-public-port-baseline — invariant: 32 random bytes hex,
// 0600, minted once per key lifetime and cached in the state dir; the bearer for every /api call.
// Minted EAGERLY before the port opens (a dashboard load must never race an unminted key).
// timingSafe compare. The file-held key itself, and its SH-12 mint-and-warn rule, is [FileHeldKey], shared with
// Claude Desktop's desktop key.
// v0.4.0: a launched session never holds this key; it holds [TurnKey], derived from it.
package splice.core.config

import splice.core.util.DaemonLog
import splice.core.util.LogSink
import splice.core.util.WallClock
import java.nio.file.FileSystemException
import java.nio.file.Path

public class MgmtKey(
    private val statePaths: StatePaths,
    log: LogSink = LogSink(DaemonLog::write),
    clock: WallClock = WallClock(System::currentTimeMillis),
) {
    private val held = FileHeldKey(
        file = statePaths.mgmtKeyFile,
        prefix = "",
        tag = "mgmt-key",
        consequence = "every existing bearer (dashboard session, scripts, the launch shim's stop hook) becomes " +
            "invalid; re-copy the key from ${statePaths.mgmtKeyFile}",
        log = log,
        clock = clock,
        firstRun = FirstMintGate(::refuseStaleRoot),
    )

    /** SH-12: non-null only when THIS process minted (fresh key); see [FileHeldKey.mintedAtMs]. */
    public val mintedAtMs: Long? get() = held.mintedAtMs

    /** Where this key lives, so [TurnKey] writes its header file beside it under the SAME state
     *  root this instance was built with (a test's temp dir, never the operator's live one). */
    internal val keyFile: Path get() = statePaths.mgmtKeyFile

    /** The keys a rotation retired, beside this one: one store per daemon, so the rotation that retires a key and
     *  every head that checks for one see the same keys. */
    public val retired: RetiredKeys = RetiredKeys(statePaths)

    public fun get(): String = held.get()

    /**
     * Replaces the key on purpose (rotation, Oct 26): mints a new one over the key file, 0600, and from then on
     * serves and matches only it, so every bearer of the old key is refused, the old turn key with it ([TurnKey]
     * derives from [get]). File first, memory second: when the write fails nothing changed.
     */
    public fun replace() {
        held.replace()
    }

    /** Constant-time bearer check (`Authorization: Bearer <key>`). */
    public fun matchesBearer(header: String?): Boolean = held.matchesBearer(header)

    /** The quiet first run goes ahead unless [StatePaths.freshStartRefusal] says the pre-0.4 root could not be
     *  inspected (PR #247 review). A key minted then would keep this root live after that one is readable again,
     *  so the start is refused instead. FileSystemException because SafeFailureText renders that class whole, and
     *  the sentence names paths only. */
    private fun refuseStaleRoot() {
        val refusal = statePaths.freshStartRefusal ?: return
        throw FileSystemException(statePaths.mgmtKeyFile.toString(), null, refusal)
    }
}
