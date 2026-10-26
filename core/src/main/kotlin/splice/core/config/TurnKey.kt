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
import splice.core.util.PathProbe
import splice.core.util.SecureFile
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Path
import java.security.MessageDigest

public class TurnKey(private val management: MgmtKey) {
    /** The last derivation and the management key it came from. A replaced management key (rotation) no longer
     *  matches it, so the next read derives the new turn key: every holder follows a rotation with no rebuild. */
    @Volatile
    private var derived: Derived? = null

    public fun get(): String = derive(management.get())

    private fun derive(mgmt: String): String =
        derived?.keyFor(mgmt) ?: Derived(mgmt, ScopedKey.derive(mgmt, TURN_SCOPE)).also { derived = it }.key

    /** Constant-time bearer check, scheme parsing shared with [MgmtKey.matchesBearer]. */
    public fun matchesBearer(header: String?): Boolean {
        val presented = BearerScheme.bearerToken(header)?.toByteArray(UTF_8) ?: return false
        val expected = get().toByteArray(UTF_8)
        return presented.size == expected.size && MessageDigest.isEqual(presented, expected)
    }

    /**
     * The 0600 header file `curl -H @file` reads, beside the management key, rewritten whenever it
     * does not hold the current key and returned by path. Called where the path is PUT INTO a
     * command, so the file the command names always exists and always matches the key the daemon
     * compares against — a stale file after a rotation would 401 every statusline tick with nothing
     * on disk saying why. A session's key helper reads the same file for its turn key (Oct 26).
     */
    public fun headerFile(): Path {
        val path = management.keyFile.resolveSibling(TURN_AUTH_HEADER_FILE)
        // A rotation can land between reading the key and writing the file, and a writer that read the old key
        // would then put it back over the new one. So each write is checked against the key current after it, and
        // written again until the two agree: the last write always names the key the daemon compares against.
        do {
            val mgmt = management.get()
            val wanted = "Authorization: Bearer ${derive(mgmt)}\n"
            // Unreadable means rewrite; writeAtomic0600 below throws on a real failure.
            if (PathProbe.text(path) != wanted) SecureFile.writeAtomic0600(path, wanted)
        } while (management.get() != mgmt)
        return path
    }

    /** One derivation, and the management key it came from. Not a data class: nothing may stringify a key. */
    private class Derived(private val from: String, val key: String) {
        fun keyFor(mgmt: String): String? = key.takeIf { from == mgmt }
    }
}

// Versioned so a future change of what this key opens can re-derive every session's key at once.
private const val TURN_SCOPE = "splice:turn-access:v1"

// The turn key as one `Authorization` header line, 0600, for `curl -H @file`: the statusline command
// names this path instead of carrying a bearer in settings.json and in curl's argv.
private const val TURN_AUTH_HEADER_FILE = "turn-auth-header"
