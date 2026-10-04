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
//
// DERIVED, not minted — the McpAccessKey shape (FEATURES.md §8): HMAC-SHA256 of the management key
// under its own scope. One-way, so holding it reveals nothing of the management key; one secret of
// record on disk, so there is no second mint path to drift and rotating the management key rotates
// this with it; and anything already holding the MgmtKey derives it, so no constructor grows.
package splice.core.config

import splice.core.auth.BearerScheme
import splice.core.auth.ScopedKey
import splice.core.util.Cancellables
import splice.core.util.SecureFile
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

public class TurnKey(private val management: MgmtKey) {
    private val value: String by lazy { ScopedKey.derive(management.get(), TURN_SCOPE) }

    public fun get(): String = value

    /** Constant-time bearer check, scheme parsing shared with [MgmtKey.matchesBearer]. */
    public fun matchesBearer(header: String?): Boolean {
        val presented = BearerScheme.bearerToken(header)?.toByteArray(UTF_8) ?: return false
        val expected = value.toByteArray(UTF_8)
        return presented.size == expected.size && MessageDigest.isEqual(presented, expected)
    }

    /**
     * The 0600 header file `curl -H @file` reads, beside the management key, rewritten whenever it
     * does not hold the current key and returned by path. Called where the path is PUT INTO a
     * command, so the file the command names always exists and always matches the key the daemon
     * compares against — a stale file after a rotation would 401 every statusline tick with nothing
     * on disk saying why.
     */
    public fun headerFile(): Path {
        val path = management.keyFile.resolveSibling(TURN_AUTH_HEADER_FILE)
        val wanted = "Authorization: Bearer $value\n"
        // ast-grep-ignore: kt-no-silent-result-collapse -- 2026-09-24: unreadable means rewrite; writeAtomic0600 below throws on a real failure
        val current = Cancellables.runCatchingCancellable { Files.readString(path) }.getOrNull()
        if (current != wanted) SecureFile.writeAtomic0600(path, wanted)
        return path
    }
}

// Versioned so a future change of what this key opens can re-derive every session's key at once.
private const val TURN_SCOPE = "splice:turn-access:v1"

// The turn key as one `Authorization` header line, 0600, for `curl -H @file`: the statusline command
// names this path instead of carrying a bearer in settings.json and in curl's argv.
private const val TURN_AUTH_HEADER_FILE = "turn-auth-header"
