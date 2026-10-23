// NEW: LAN share, phase 2, milestone 6 (docs/specs/lan-share.md). The daemon's half of sessions across machines: the
// mirrors of other machines' sessions, hosted where Claude Code keeps its own (its sessions registry, and its sockets
// folder: `$XDG_RUNTIME_DIR/cc-socks`, else `/tmp/cc-socks`, Claude Code 2.1.309's rule), and the link to each machine
// this one joined. The share listener hands a joined machine's sessions to the same mirrors; a stop ends the links and
// withdraws every mirror.
package splice.app.daemon

import kotlinx.coroutines.cancel
import splice.core.config.StatePaths
import splice.core.config.UserHome
import splice.core.util.EnvReader
import splice.core.util.LogSink
import splice.sessions.mirror.MirrorPlace
import splice.sessions.mirror.SessionMirrors
import splice.share.ShareState
import splice.share.remote.RemoteLinks
import splice.share.remote.RemoteSessions
import splice.upstream.LifecycleScope
import splice.upstream.codemode.ProcessDispatchers
import java.nio.file.Path

// why: where Claude Code puts its sockets folder when no runtime folder is set, as on a Mac.
private const val CLIENT_TMP = "/tmp"

// why: the name of Claude Code's sockets folder, under the runtime folder or CLIENT_TMP.
private const val CLIENT_SOCKETS = "cc-socks"

internal class SharedSessions(env: EnvReader, statePaths: StatePaths, private val log: LogSink) {
    private val scope = LifecycleScope(ProcessDispatchers().io())

    /** The mirrors this daemon hosts: a joined machine's, which the share listener keeps, and those of each machine
     *  this one joined. */
    val mirrors: SessionMirrors = SessionMirrors(
        MirrorPlace(
            UserHome.claudeDir(env).resolve("sessions"),
            Path.of(env("XDG_RUNTIME_DIR")?.takeIf { it.isNotBlank() } ?: CLIENT_TMP, CLIENT_SOCKETS),
            ShareState(statePaths).mirrorsLog,
        ),
        scope,
        ProcessDispatchers().io(),
        log,
    )

    /** Links each machine in [remotes] to this one's sessions; nothing when this one joined none. */
    fun start(remotes: RemoteLinks) {
        RemoteSessions(remotes, mirrors, log).start(scope)
    }

    /** Ends every link and withdraws every mirror. */
    fun stop() {
        scope.cancel()
        mirrors.close()
    }
}
