// NEW: V4-32 — the tool-name cap that unbroke claude-muse. The live failure is pinned by name:
// api.meta.ai answered `name` must be at most 64 characters, got 68 for the operator's own
// mcp__plugin_desktop-commander_desktop-commander__read_process_output.
package splice.dialect.anthropic

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.util.LogSink
import java.security.MessageDigest

class ToolNameShortenerTest {

    @Test
    fun `the exact name that 400d the first live muse turn fits after shortening`() {
        val shortener = ToolNameShortener(MUSE_CAP)
        assertEquals(68, LIVE_FAILURE.length, "the reported length was 68; if this drifts the fixture is stale")
        val short = shortener.shorten(LIVE_FAILURE)
        assertEquals(MUSE_CAP, short.length)
        assertEquals(LIVE_FAILURE, shortener.restore(short))
    }

    @Test
    fun `shortening is deterministic so a replayed tool_use maps to its own declaration`() {
        // Claude Code replays earlier assistant turns verbatim. The declaration is shortened on the
        // tools array and the replayed tool_use block is shortened separately; if those two
        // disagreed, the upstream would see a call naming a tool it was never offered.
        val declarations = ToolNameShortener(MUSE_CAP)
        val replays = ToolNameShortener(MUSE_CAP)
        assertEquals(declarations.shorten(LIVE_FAILURE), replays.shorten(LIVE_FAILURE))
    }

    @Test
    fun `distinct long names sharing a prefix do not collide`() {
        val shortener = ToolNameShortener(MUSE_CAP)
        val a = "mcp__plugin_desktop-commander_desktop-commander__read_process_output"
        val b = "mcp__plugin_desktop-commander_desktop-commander__read_process_outputs"
        assertNotEquals(shortener.shorten(a), shortener.shorten(b))
        assertEquals(a, shortener.restore(shortener.shorten(a)))
        assertEquals(b, shortener.restore(shortener.shorten(b)))
    }

    @Test
    fun `a name already within the cap is untouched, and so is every name when the cap is off`() {
        val capped = ToolNameShortener(MUSE_CAP)
        assertEquals("Bash", capped.shorten("Bash"))
        val exactly = "x".repeat(MUSE_CAP)
        assertEquals(exactly, capped.shorten(exactly))

        val off = ToolNameShortener()
        assertFalse(off.active)
        assertEquals(LIVE_FAILURE, off.shorten(LIVE_FAILURE))
        // Every head but Muse runs with the cap off; an unshortened name must survive the response
        // path unchanged rather than being looked up and lost.
        assertEquals(LIVE_FAILURE, off.restore(LIVE_FAILURE))
    }

    @Test
    fun `the shortened name still satisfies the wire's name grammar`() {
        val short = ToolNameShortener(MUSE_CAP).shorten(LIVE_FAILURE)
        assertTrue(NAME_GRAMMAR.matches(short), "not a legal tool name: $short")
    }

    @Test
    fun `the tool sanitizer rewrites the declaration the upstream validates`() {
        val shortener = ToolNameShortener(MUSE_CAP)
        val sanitizer = PassthroughToolSanitizer(
            PassthroughQuirks(providerTag = "muse", toolNameCap = MUSE_CAP),
            PassthroughCacheControl(false),
            shortener,
        )
        val tools = buildJsonArray {
            add(
                buildJsonObject {
                    put("name", LIVE_FAILURE)
                    put("description", "")
                },
            )
        }
        val name = (sanitizer.sanitizeTools(tools)[0] as JsonObject)["name"]!!.jsonPrimitive.content
        assertEquals(MUSE_CAP, name.length, "the sanitizer must emit a name the upstream accepts")
        assertEquals(LIVE_FAILURE, shortener.restore(name))
    }

    // ---- V4-40: the two silent failures, walled -------------------------------------------------

    @Test
    fun `a colliding digest is logged and the first name wins`() {
        // Two DIFFERENT names that shorten to the SAME string: identical 55-character prefix, and a
        // 4-byte digest that agrees. Searched here on the same one-way truncation the class uses, so
        // this is a REAL collision rather than a mocked one. The search deliberately does not go
        // through shorten() — tens of thousands of probes would blow the map bound and start
        // refusing — so the assertEquals(shortFirst, shortSecond) below is what proves the fixture
        // still collides through the production path if the digest or the prefix length changes.
        val prefix = LIVE_FAILURE.take(MUSE_CAP - DIGEST_CHARS - 1)
        val seen = HashMap<String, String>()
        var pair: Pair<String, String>? = null
        var i = 0
        while (pair == null && i < COLLISION_SEARCH_LIMIT) {
            // Padded well past MUSE_CAP: a candidate at or under the cap is returned UNCHANGED by
            // shorten(), so it can never collide and the search would run to its limit.
            val candidate = "$prefix-${i.toString().padStart(CANDIDATE_PAD, '0')}"
            val collidedWith = seen.put(digestOf(candidate), candidate)
            if (collidedWith != null && collidedWith != candidate) pair = collidedWith to candidate
            i++
        }
        val (first, second) = requireNotNull(pair) { "no collision found in $COLLISION_SEARCH_LIMIT tries" }

        val lines = mutableListOf<String>()
        val shortener = ToolNameShortener(MUSE_CAP, LogSink { message -> lines += message })
        val shortFirst = shortener.shorten(first)
        val shortSecond = shortener.shorten(second)

        assertEquals(shortFirst, shortSecond, "the fixture must collide through the real shortening")
        assertEquals(first, shortener.restore(shortFirst), "the first original must survive")
        assertNotEquals(second, shortener.restore(shortSecond), "a collision must never overwrite the map")
        assertTrue(lines.any { "collision" in it }, "a collision must be loud, not silent: $lines")
    }

    @Test
    fun `past the bound no unrestorable short name reaches the client`() {
        val lines = mutableListOf<String>()
        val shortener = ToolNameShortener(MUSE_CAP, LogSink { message -> lines += message })
        // Fill exactly to the bound. The prefix is long enough that every name shares it, so each
        // takes its own slot by digest, and the assertions pin the bound from both sides: a smaller
        // production bound refuses one of these, a larger one fails to refuse the overflow.
        val prefix = "x".repeat(MUSE_CAP + 6)
        val filled = (0 until BOUND_ENTRIES).map { prefix + it.toString().padStart(6, '0') }
        for (name in filled) {
            assertNotEquals(name, shortener.shorten(name), "still recording below the bound")
        }

        val overflow = "${prefix}overflow"
        val returned = shortener.shorten(overflow)
        // The acceptance is about RESTORABILITY: whatever comes back must resolve to the name the
        // client offered. Failing closed returns the original, which satisfies that by construction.
        assertEquals(overflow, shortener.restore(returned), "an unrestorable short name reached the client")
        assertEquals(overflow, returned, "fail closed by handing up the original, for a clear upstream error")
        assertTrue(lines.any { "full" in it }, "the refusal must be loud, not silent: $lines")

        // The bound governs what may be ADDED, never what a recorded name means: a name taken before
        // the map filled must still resolve after it, or a long session's early tools start failing.
        val firstShort = shortener.shorten(filled.first())
        assertEquals(filled.first(), shortener.restore(firstShort), "a recorded name must stay recorded")
    }

    /** The class's own truncation, reproduced so the collision search can run without filling the
     *  map under test. Mirrors a 4-byte SHA-256 prefix, lower-case hex. */
    private fun digestOf(name: String): String =
        MessageDigest.getInstance(SHA_256)
            .digest(name.toByteArray(Charsets.UTF_8))
            .take(DIGEST_BYTES)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and BYTE_MASK) }
}

private const val MUSE_CAP = 64
private const val LIVE_FAILURE = "mcp__plugin_desktop-commander_desktop-commander__read_process_output"
private val NAME_GRAMMAR = Regex("^[a-zA-Z0-9_-]{1,64}$")

// V4-40: mirrors the shortener's private constants so the walls can pin them from the OUTSIDE — a
// production bound that moves either way fails these tests rather than silently widening the hole.
private const val SHA_256 = "SHA-256"
private const val DIGEST_BYTES = 4
private const val DIGEST_CHARS = DIGEST_BYTES * 2
private const val BYTE_MASK = 0xFF
private const val BOUND_ENTRIES = 4096

/** Keeps a collision candidate comfortably over MUSE_CAP, so shorten() actually shortens it. */
private const val CANDIDATE_PAD = 10

/** Birthday bound for a 4-byte digest is roughly 65 thousand probes; this is a wide margin, and the
 *  loop exits at the first collision, so the usual cost is far below it. */
private const val COLLISION_SEARCH_LIMIT = 500_000
