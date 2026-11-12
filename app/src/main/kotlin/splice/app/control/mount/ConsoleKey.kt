// NEW: Oct 26, 2026 — the credential the console holds, and the one way a browser is handed it. Until this day
// `splice console` sent the browser to an address that carried the management key itself (#k=…), and on Sep 29 a
// browser tool printed such an address into a conversation that went to a model provider. The page now carries a code
// instead, minted here for a caller the management key let in and good for one trade within [CODE_LIFETIME_MS], so an
// address left in a history, a log or a tool's output holds a code that is spent or expired and opens nothing.
//
// A code trades for this key, never for the management key: HMAC of the management key under its own scope (ScopedKey,
// the TurnKey shape). A browser then holds nothing a turn key or the MCP key derives from, and replacing the management
// key revokes every console at once. It is derived from the management key the daemon holds at each use, so it follows
// a key replaced in place (MgmtKey.replace) with no cache to clear, and a code still open then opens nothing. It opens
// the control routes the management key opens, which is what the console reads and changes, and no peer's own rows
// (ControlGuard).
package splice.app.control.mount

import splice.core.auth.BearerScheme
import splice.core.auth.ScopedKey
import splice.core.config.MgmtKey
import splice.core.util.WallClock
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.HexFormat

// Versioned like the turn key's scope, so a change of what this key opens can re-derive every console's key at once.
private const val CONSOLE_SCOPE = "splice:console-access:v1"

/** How long a code signs a console in for. The page trades it within a second of `splice console`; a person who opens
 *  the page `splice console --no-browser` printed has two minutes, as its line says. */
internal const val CODE_LIFETIME_MS = 2 * 60_000L

/** 256 random bits per code. */
private const val CODE_BYTES = 32

/** The codes open at once. Minting past this drops the oldest, so asking a thousand times holds no more than this. */
private const val MAX_OPEN_CODES = 64

internal class ConsoleKey(
    private val management: MgmtKey,
    private val clock: WallClock = WallClock(System::currentTimeMillis),
) {
    private val random = SecureRandom()
    private val hex = HexFormat.of()

    /** Each open code's SHA-256, never the code itself, and what it was minted under, oldest first. */
    private val open = LinkedHashMap<String, Minted>()

    /** The key the console holds, derived from the management key the daemon holds now. */
    fun get(): String = ScopedKey.derive(management.get(), CONSOLE_SCOPE)

    /** A fresh single-use code, for a caller the management key already let in. */
    fun code(): String {
        val code = hex.formatHex(ByteArray(CODE_BYTES).also(random::nextBytes))
        val held = digest(code)
        val now = clock()
        val minted = Minted(now + CODE_LIFETIME_MS, digest(get()))
        synchronized(open) {
            open.values.removeIf { it.until <= now }
            while (open.size >= MAX_OPEN_CODES) open.remove(open.keys.first())
            open[held] = minted
        }
        return code
    }

    /** The console key for a code minted here, unspent, within its lifetime and under the management key the daemon
     *  holds now, and null for any other. A code is spent by the first trade that names it, in time or not, so two
     *  pages racing for one code never both get the key. One minted before the management key was replaced opens
     *  nothing, so a code taken with the old key cannot carry whoever took it across the replacement. */
    fun trade(code: String): String? {
        val held = digest(code)
        val minted = synchronized(open) { open.remove(held) } ?: return null
        val key = get()
        return key.takeIf { clock() < minted.until && digest(it) == minted.under }
    }

    /** Constant-time bearer check, scheme parsing shared with [MgmtKey.matchesBearer]. */
    fun matchesBearer(header: String?): Boolean {
        val presented = BearerScheme.bearerToken(header)?.toByteArray(UTF_8) ?: return false
        val expected = get().toByteArray(UTF_8)
        return presented.size == expected.size && MessageDigest.isEqual(presented, expected)
    }

    private fun digest(text: String): String =
        hex.formatHex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray(UTF_8)))

    /** When an open code stops opening anything, and the SHA-256 of the console key it was minted under. */
    private data class Minted(val until: Long, val under: String)
}
