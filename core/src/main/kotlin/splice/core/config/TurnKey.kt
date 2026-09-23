// NEW: v0.4.0 security split — the credential a LAUNCHED CLIENT holds, separate from the management
// key. Before this, LaunchService planted the management key itself as every gateway session's
// ANTHROPIC_AUTH_TOKEN, and the statusline command carried it inline in settings.json and in curl's
// argv. A session's environment is inherited by every tool the model runs, so `printenv` in any
// session handed over the whole control plane — topology (every head's system prompt), every
// transcript, daemon restart — and it did reach session transcripts that went to model providers.
//
// What this key opens, and nothing more: a head's TURN routes (/v1/messages, count_tokens,
// /v1/models) and the two session-scoped control routes a session's own hooks call (/statusline,
// /hooks/resume). The management key keeps everything else, and is still accepted for turns so a
// session launched before the split keeps working until it is relaunched.
package splice.core.config

import splice.core.util.Cancellables
import splice.core.util.DaemonLog
import splice.core.util.LogSink
import splice.core.util.SecureFile
import splice.core.util.WallClock
import java.nio.file.Files
import java.nio.file.Path

public class TurnKey(
    statePaths: StatePaths,
    log: LogSink = LogSink(DaemonLog::write),
    clock: WallClock = WallClock(System::currentTimeMillis),
) {
    // Beside the management key, resolved the way ConsoleWiring and ClaudeLogins resolve their own
    // state files: StatePaths owns the ROOT, and each store names its file under it.
    private val keyFile: Path = statePaths.stateDir.resolve(TURN_KEY_FILE)
    private val headerPath: Path = statePaths.stateDir.resolve(TURN_AUTH_HEADER_FILE)

    private val key = StateKey(
        path = keyFile,
        label = "turn-key",
        consequence = "every session launched before it gets 401 on its next turn until it is relaunched",
        log = log,
        clock = clock,
    )

    public val mintedAtMs: Long? get() = key.mintedAtMs

    public fun get(): String = key.get()

    /** Constant-time check of a raw presented credential (no scheme parsing). */
    public fun matches(presented: String?): Boolean = key.matches(presented)

    public fun matchesBearer(header: String?): Boolean = key.matchesBearer(header)

    /**
     * The 0600 header file `curl -H @file` reads, rewritten whenever it does not hold the current
     * key and returned by path. Called where the path is PUT INTO a command, so the file the command
     * names always exists and always matches the key the daemon compares against — a stale file
     * after a key rotation would 401 every statusline tick with nothing on disk saying why.
     */
    public fun headerFile(): Path {
        val wanted = "Authorization: Bearer ${get()}\n"
        val current = Cancellables.runCatchingCancellable { Files.readString(headerPath) }.getOrNull()
        if (current != wanted) SecureFile.writeAtomic0600(headerPath, wanted)
        return headerPath
    }
}

// The turn key's own file, 0600, beside mgmt-key in the state dir. A NAME rather than a path so the
// state root stays StatePaths' alone (kt-state-paths-single-source).
private const val TURN_KEY_FILE = "turn-key"

// The turn key as one `Authorization` header line, 0600, for `curl -H @file`: the statusline command
// names this path instead of carrying a bearer in settings.json and in curl's argv.
private const val TURN_AUTH_HEADER_FILE = "turn-auth-header"
