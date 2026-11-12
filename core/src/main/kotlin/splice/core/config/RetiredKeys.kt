// NEW (Oct 26, 2026, PR #247 review round 4): the keys a rotation retired, known by their SHA-256 so splice still
// knows them as its own. `splice rotate-key` replaces the management key and the turn key made from it, and from then
// on both open nothing. A session launched before the key helper still holds the old turn key in ANTHROPIC_AUTH_TOKEN,
// and a command started inside it, on a head that forwards the person's own Anthropic login, would carry that key to
// the vendor, which refuses it 401 while Claude Code sends it again until its caller gives up (the Oct 13 storm). The
// launch shim drops one of splice's keys from what a launch inherits, a forwarding head refuses one, and `splice
// sessions relaunch` relaunches a session that holds one: each can do that only for a key it knows. A digest is
// enough to know a key by and opens nothing, so the file beside the management key holds digests alone, one per line,
// 0600, and the shim reads it too.
package splice.core.config

import splice.core.auth.ScopedKey
import splice.core.util.PathProbe
import splice.core.util.SecureFile
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Path
import java.security.MessageDigest

public class RetiredKeys(statePaths: StatePaths) {
    private val file: Path = statePaths.mgmtKeyFile.resolveSibling(RETIRED_KEYS_FILE)

    /** The digests known so far: read from [file] once, then kept current by [retire]. */
    @Volatile
    private var known: Set<String>? = null

    private val lock = Any()

    /** Whether [presented] is a key a rotation retired. */
    public fun holds(presented: String): Boolean = digest(presented) in digests()

    /** Whether [presented] is one of splice's keys while [management] is the management key: that key, the turn key
     *  made from it ([TurnKey]), or one a rotation retired. */
    public fun ownKey(presented: String, management: String): Boolean =
        presented == management || presented == ScopedKey.derive(management, TURN_SCOPE) || holds(presented)

    /** Remembers [management], the management key about to be replaced, and the turn key made from it ([TurnKey]), so
     *  both are still known as splice's once they open nothing. Throws when the file cannot be written. */
    public fun retire(management: String) {
        synchronized(lock) {
            val next = digests() + listOf(management, ScopedKey.derive(management, TURN_SCOPE)).map(::digest)
            SecureFile.writeAtomic0600(file, next.joinToString("") { "$it\n" })
            known = next
        }
    }

    private fun digests(): Set<String> = known ?: synchronized(lock) { known ?: read().also { known = it } }

    /** What [file] holds. Not there, or unreadable, it holds nothing: no key is taken for a retired one. */
    private fun read(): Set<String> =
        PathProbe.text(file)?.lineSequence()?.map(String::trim)?.filter(String::isNotEmpty)?.toSet().orEmpty()

    private fun digest(key: String): String =
        MessageDigest.getInstance("SHA-256").digest(key.toByteArray(UTF_8)).joinToString("") { "%02x".format(it) }
}

// Beside the management key, where the launch shim reads it (splice-launch, drop_own_token).
private const val RETIRED_KEYS_FILE = "retired-keys"
