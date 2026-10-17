// NEW: V4-32 — api.meta.ai caps tool `name` at 64 characters. Anthropic's own endpoint does not,
// and Claude Code routinely ships MCP names past it: an installed plugin server spells its tools
// mcp__plugin_<plugin>_<server>__<tool>, which reaches 83 characters in the operator's own session
// and the first live Muse turn at 68 came back rejected. splice sits on both directions of this
// wire, so splice authors the name that crosses it — shorten on the way out, restore on the way back.
//
// DETERMINISTIC ON PURPOSE. Claude Code replays earlier assistant turns verbatim, so a tool_use
// block from three turns ago arrives again on the next request; a counter or a random suffix would
// give it a different short name each time and the upstream would see two tools where there is one.
// Hashing the original means the same name always shortens to the same string, with no per-turn
// state and nothing to invalidate.
//
// The reverse map exists because the shortening is lossy: the model answers with the name it was
// GIVEN, and Claude Code dispatches on the name it SENT, so the response side has to put the
// original back or the client cannot find the tool.
//
// V4-40/V4-351: a digest collision or native name matching an earlier alias must not give two
// client tools one wire identity. The first 8-digit alias is byte-stable; a conflict extends the
// digest while preserving existing mappings. At the map bound, an over-cap name travels unchanged
// for a clear upstream rejection; a conflicting native name fails locally rather than dispatching
// a different tool. Both dialects share this one per-provider map.
package splice.upstream

import splice.core.util.DaemonLog
import splice.core.util.LogSink
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

public class ToolNameShortener(
    private val cap: Int = 0,
    /** The anomaly channel. Both guards below are silent without it, and a silent mis-dispatch is
     *  the worst outcome available on this wire, so each reports (wall kt-no-println). */
    private val log: LogSink = LogSink(DaemonLog::write),
) {

    /** wire name -> original, shared by one head's request and response paths. Native names are
     *  reserved too, so an overlong tool cannot take a name the client already offered. */
    private val originals = ConcurrentHashMap<String, String>()

    /** One line per EPISODE, not per name: past the bound every further name is refused, and tools
     *  are re-sent on every turn, so an unlatched line would repeat per tool per turn. */
    private val fullLogged = AtomicBoolean(false)

    /** False for every head that did not ask for a cap, which is all of them but Muse. A cap too
     *  small to hold a prefix AND the digest is treated as off rather than silently emitting a name
     *  that is nothing but hash. */
    public val active: Boolean get() = cap >= MIN_CAP

    public fun shorten(name: String): String {
        if (!active) return name
        return synchronized(originals) { native(name) ?: assign(name) }
    }

    private fun native(name: String): String? {
        if (name.length > cap) return null
        val prior = originals[name]
        if (prior != null && prior != name) return null
        if (prior == null && originals.size < MAX_ENTRIES) originals[name] = name
        return name
    }

    private fun assign(name: String): String {
        val digest = digest(name)
        for (chars in DIGEST_CHARS..minOf(MAX_DIGEST_CHARS, cap - 2) step DIGEST_STEP) {
            val short = name.take(cap - chars - 1) + "_" + digest.take(chars)
            val prior = originals[short]
            if (prior == name) return short // replay stays stable even after the bound is full
            if (prior != null) {
                log("[toolname] digest collision: ${short.take(LOG_CHARS)}; trying longer alias\n")
                continue
            }
            if (originals.size >= MAX_ENTRIES) return refuse(name)
            originals[short] = name
            return short
        }
        error("tool-name alias namespace exhausted by collisions")
    }

    /** Unknown names pass through untouched: a head with no cap never populates the map, and a
     *  name the upstream invented was never ours to rewrite. */
    public fun restore(name: String): String = originals[name] ?: name

    /** Beyond the bound an over-cap name travels unchanged for a clear upstream error. A native
     *  name that conflicts with an issued alias fails locally: passing it unchanged would dispatch
     *  a different client tool when the response restores the earlier alias. */
    private fun refuse(name: String): String {
        if (name.length <= cap) error("native tool name conflicts with an earlier alias at the map bound")
        if (fullLogged.compareAndSet(false, true)) {
            log(
                "[toolname] shortener map full at $MAX_ENTRIES entries; further over-cap names are " +
                    "no longer shortened, so the upstream rejects them instead of the client " +
                    "receiving a name it cannot resolve\n",
            )
        }
        return name
    }

    private fun digest(name: String): String =
        MessageDigest.getInstance(SHA_256)
            .digest(name.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and BYTE_MASK) }
}

private const val SHA_256 = "SHA-256"

// why: the first alias keeps the prior four-byte digest for prompt-cache byte stability.
private const val DIGEST_CHARS = 8

// why: extend by two digest bytes per conflict while retaining an original-name prefix at cap 64.
private const val DIGEST_STEP = 4

// why: SHA-256 has 64 hex digits; leave at least one original-name character before the suffix.
private const val MAX_DIGEST_CHARS = 60

// why: Java bytes are signed; mask them before writing two hex digits per byte.
private const val BYTE_MASK = 0xFF

// why: cap a head's lifetime alias map; beyond this an overlong name fails closed at the backend.
private const val MAX_ENTRIES = 4096
private const val MIN_CAP = DIGEST_CHARS + 2

// why: bound the name fragment in collision logs; tools can carry untrusted long names.
private const val LOG_CHARS = 64
