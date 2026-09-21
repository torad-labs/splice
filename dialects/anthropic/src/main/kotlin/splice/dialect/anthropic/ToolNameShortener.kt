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
// V4-40 — THE TWO WAYS THIS COULD FAIL WITHOUT SAYING SO, both closed here. Neither is reachable at
// the tool counts a real head sees; both are worth the lines precisely because neither announces
// itself, and both were found by adversarial review rather than by a user.
//   (1) A DIGEST COLLISION overwrote the map entry, so `restore` handed the client a tool it never
//       called — a mis-dispatched call, attributed to the model rather than to splice. The FIRST
//       original now wins and the collision is logged.
//   (2) PAST THE BOUND the map stopped recording while shortening continued, which is the worst of
//       the two states: the request path emitted a short name the response path could not resolve,
//       so the client received a name matching no tool it had offered, and nothing anywhere said
//       why. It now FAILS CLOSED — the original travels and the upstream answers with its own clear
//       name-length error — because a loud rejection is a better outcome than a silent identity swap.
package splice.dialect.anthropic

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

    /** short -> original, written on the request path and read on the response path. One instance
     *  is shared by a head's request builder and its stream translators, so a name shortened on the
     *  way out is restorable on the way back for the life of the head. Bounded: a head meets tens
     *  of distinct tool names, and the bound means a pathological client cannot grow it forever. */
    private val originals = ConcurrentHashMap<String, String>()

    /** One line per EPISODE, not per name: past the bound every further name is refused, and tools
     *  are re-sent on every turn, so an unlatched line would repeat per tool per turn. */
    private val fullLogged = AtomicBoolean(false)

    /** False for every head that did not ask for a cap, which is all of them but Muse. A cap too
     *  small to hold a prefix AND the digest is treated as off rather than silently emitting a name
     *  that is nothing but hash. */
    public val active: Boolean get() = cap >= MIN_CAP

    public fun shorten(name: String): String {
        if (!active || name.length <= cap) return name
        val short = name.take(cap - DIGEST_CHARS - 1) + "_" + digest(name)
        // FIRST, because a replayed turn re-shortens the same name and must get the SAME answer even
        // once the map is full: the bound governs what may be ADDED, never what a recorded name
        // means. Checking it after the bound would make a long session's early tools start failing.
        if (originals[short] == name) return short
        return if (originals.size >= MAX_ENTRIES) refuse(name) else record(short, name)
    }

    /** Unknown names pass through untouched: a head with no cap never populates the map, and a
     *  name the upstream invented was never ours to rewrite. */
    public fun restore(name: String): String = originals[name] ?: name

    /** Records [short] for [name] and answers the short form. FIRST WRITER WINS: a digest collision
     *  leaves the earlier original in place and says so. Overwriting would silently hand the client
     *  the wrong tool, and keeping the first at least means every replay of either name resolves the
     *  same way for the life of the head. */
    private fun record(short: String, name: String): String {
        val prior = originals.putIfAbsent(short, name)
        if (prior != null && prior != name) {
            log(
                "[toolname] digest collision: ${short.take(LOG_CHARS)} already resolves to " +
                    "${prior.take(LOG_CHARS)} and now also to ${name.take(LOG_CHARS)}; keeping the " +
                    "first, so a replayed turn stays stable\n",
            )
        }
        return short
    }

    /** Past the bound a short name can never be restored, so it is not issued: the original travels
     *  and the upstream answers with its own clear error. This is the one outcome refused outright —
     *  a short name the response path cannot resolve reaches the client as a tool it never offered,
     *  which no surface reports and nothing in the log explains. */
    private fun refuse(name: String): String {
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
            .take(DIGEST_BYTES)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and BYTE_MASK) }
}

private const val SHA_256 = "SHA-256"
private const val DIGEST_BYTES = 4
private const val DIGEST_CHARS = DIGEST_BYTES * 2
private const val BYTE_MASK = 0xFF
private const val MAX_ENTRIES = 4096
private const val MIN_CAP = DIGEST_CHARS + 2
private const val LOG_CHARS = 64
