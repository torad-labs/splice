// NEW: CX-02's structural half — a compaction is built EXACTLY like a turn, in every dialect that
// exists and every dialect that will (ported from the cx_02_compaction_is_a_turn wall, §2.7).
//
// THE LAW (operator, 2026-09-05): the compaction request has to use the same model, the same
// reasoning, the same tools, everything as the session. The backend's prompt cache is an
// exact-prefix match, so every compact-only reshaping a builder does — a directive appended to
// instructions, tools or tool_choice stripped, tool results folded, images dropped, an effort pin —
// moves the prefix from token zero, and the most expensive turn class there is reads the whole
// transcript cold. Measured 2026-09-05: compact cached_tokens=0 on every model, on every dialect.
//
// WHAT GRADES WHAT, and which one is the real guarantee. The BYTE-IDENTITY CANARY is the real one:
// each dialect's builder test builds one body as a turn and as a compaction and asserts the request
// bytes are equal. It is transitively complete — it catches a reshaping anywhere in the builder's
// call graph, including a helper file nobody thought to list. This law does NOT re-grade that; it
// makes the canary non-optional, which is the one thing the canary cannot do for itself: a dialect
// that ships a request builder with no canary test is invisible to a passing test suite.
//
// THE DENOMINATOR IS THE TREE. The wall this replaces carried two hand-written maps: five builder
// paths and three canary paths. A fourth dialect is green in both on the day it lands. Here, every
// *RequestBuilder.kt under dialects/ is found by sweeping, and each one's module must carry a test
// containing the canary sentence. Add a dialect, and the law names it.
//
// THE TOKEN BAN IS SECONDARY AND SAYS SO. Naming a compact-shaping symbol in a builder is the early
// signal; the canary is the proof. The ban reads comment-stripped code, because all three builders
// carry long comments ABOUT compact shaping explaining why they no longer do it, and a scanner that
// could not tell prose from code would red the very files that document the law.
//
// SCOPED TO BUILDERS, NOT TO dialects/. Widening the ban to every dialect source was measured and
// rejected: seven reads of the compact flag live in the RESPONSE path and the cache policy —
// ResponsesTurnSeams.kt:57-77, ReasoningCachePolicy.kt:23, ResponsesOutcomePayload.kt:41 — where
// consulting it is correct. A law that reds correct code teaches readers to ignore it.
package splice.quality

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal object CompactionIsATurn {
    const val CANARY: String = "compaction is built byte-identical to a turn"

    private val BUILDER = Regex("^dialects/([^/]+)/src/main/.*/[A-Za-z]*RequestBuilder\\.kt$")
    private val TEST_OF_MODULE = Regex("^dialects/([^/]+)/src/test/.*\\.kt$")

    /** A compact-only reshaping of the request, by name. */
    private val FORBIDDEN = listOf(
        "withCompactDirective",
        "compactDirective",
        "CompactInstructions",
        "compactAwareInstructions",
        "compactAwareSystem",
        "compactEffortPin",
        "compactEffort",
        "COMPACT MODE",
    )

    /** Reading the flag at all. Allowed ONLY where it is handed to TurnMeta for the response side. */
    private val COMPACT_READ = Regex("\\b(?:opts|meta)\\.compact\\b|\\bcompact\\b\\s*\\)|!compact\\b|\\(compact\\)")
    private val COMPACT_HANDOFF = Regex("compact\\s*=\\s*(?:opts\\.)?compact\\b")

    /** Pure: main sources and test sources, each repo-relative path -> text. */
    fun audit(main: Map<String, String>, tests: Map<String, String>): List<String> {
        val builders = main.keys.filter { BUILDER.matches(it) }.sorted()
        if (builders.isEmpty()) {
            return listOf(
                "no *RequestBuilder.kt under dialects/ was swept — this law's denominator is the tree, so an " +
                    "empty sweep means the extractor or the project map broke, not that every dialect complies",
            )
        }
        val problems = mutableListOf<String>()
        builders.forEach { rel ->
            problems += canaryProblems(rel, tests)
            problems += shapingProblems(rel, main.getValue(rel))
        }
        return problems
    }

    private fun canaryProblems(builderPath: String, tests: Map<String, String>): List<String> {
        val module = BUILDER.find(builderPath)!!.groupValues[1]
        val covered = tests.any { (path, text) ->
            TEST_OF_MODULE.find(path)?.groupValues?.get(1) == module && text.contains(CANARY)
        }
        return if (covered) {
            emptyList()
        } else {
            listOf(
                "dialects/$module ships $builderPath with no byte-identity canary: no test in that module " +
                    "contains \"$CANARY\". The canary is what actually proves a compaction is built like a turn — " +
                    "it compares the two request bodies byte for byte, so it catches a reshaping anywhere in the " +
                    "builder's call graph. Without it this dialect's prompt cache can go to zero with every test green",
            )
        }
    }

    private fun shapingProblems(rel: String, source: String): List<String> {
        val code = KotlinText.stripComments(source)
        val problems = mutableListOf<String>()
        FORBIDDEN.filter { code.contains(it) }.forEach { token ->
            problems += "$rel names `$token` — a compact-only reshaping of the request. The compaction must be " +
                "built EXACTLY like a turn: same model, same tools, same tool_choice, same effort"
        }
        KotlinText.splitLines(code).forEachIndexed { index, line ->
            if (COMPACT_READ.containsMatchIn(line) && !COMPACT_HANDOFF.containsMatchIn(line)) {
                problems += "$rel:${index + 1} reads the compact flag while building the request — `${line.trim()}`. " +
                    "The flag reaches TurnMeta for the RESPONSE side only; a builder that branches on it is " +
                    "shaping the request, which moves the cache prefix from token zero"
            }
        }
        return problems
    }
}

class CompactionIsATurnLawTest {
    private val map = ProjectMap.fromSystemProperties()

    @Test
    fun `every dialect builds a compaction exactly like a turn - CX-02`() {
        val main = KotlinText.kotlinFiles(map).associate { f -> KotlinText.rel(map, f) to f.readText() }
        val tests = KotlinText.kotlinFiles(map, "src/test").associate { f -> KotlinText.rel(map, f) to f.readText() }
        val problems = CompactionIsATurn.audit(main, tests)
        assertTrue(problems.isEmpty()) {
            problems.joinToString(separator = "\n  - ", prefix = "COMPACTION IS A TURN (CX-02) violated:\n  - ")
        }
    }

    @Test
    fun `the law can actually fail - a dialect that ships a builder with no canary`() {
        assertEquals(emptyList<String>(), CompactionIsATurn.audit(MAIN, TESTS), "the compliant pair is green")

        assertHit(CompactionIsATurn.audit(MAIN, emptyMap()), "no byte-identity canary") {
            "a builder whose module has no canary test must be RED"
        }
        assertHit(
            CompactionIsATurn.audit(MAIN, mapOf(TEST_PATH to "fun `builds a request`() {}")),
            "no byte-identity canary",
        ) { "a test file that exists but does not carry the canary sentence is not coverage" }
        assertHit(
            CompactionIsATurn.audit(MAIN + (OTHER_BUILDER to "class OtherRequestBuilder"), TESTS),
            "dialects/newdialect",
        ) { "a NEW dialect's builder is named the day it lands, which no hand-written map can do" }
    }

    @Test
    fun `the law can actually fail - the token ban and the flag read, but not the prose`() {
        FORBIDDEN_SAMPLES.forEach { token ->
            assertHit(CompactionIsATurn.audit(mapOf(BUILDER_PATH to "val x = $token(body)"), TESTS), token) {
                "`$token` in a builder must be RED"
            }
        }
        assertHit(
            CompactionIsATurn.audit(mapOf(BUILDER_PATH to "if (opts.compact) instructions += DIRECTIVE"), TESTS),
            "reads the compact flag while building",
        ) { "branching on the flag while building the request must be RED" }
        assertEquals(
            emptyList<String>(),
            CompactionIsATurn.audit(mapOf(BUILDER_PATH to "meta = TurnMeta(compact = opts.compact)"), TESTS),
            "handing the flag to TurnMeta for the response side is the one allowed read",
        )
        assertEquals(
            emptyList<String>(),
            CompactionIsATurn.audit(mapOf(BUILDER_PATH to PROSE), TESTS),
            "the comment explaining why the builder no longer reshapes on compact is prose, not a reshaping",
        )
    }

    @Test
    fun `the law refuses when it sweeps no builder at all`() {
        assertHit(CompactionIsATurn.audit(emptyMap(), TESTS), "empty sweep means the extractor") {
            "no builder swept must REFUSE, never pass"
        }
    }

    private companion object {
        const val BUILDER_PATH = "dialects/openai-chat/src/main/kotlin/splice/dialect/chat/ChatRequestBuilder.kt"
        const val TEST_PATH = "dialects/openai-chat/src/test/kotlin/splice/dialect/chat/ChatRequestBuilderTest.kt"
        const val OTHER_BUILDER = "dialects/newdialect/src/main/kotlin/splice/dialect/new/NewRequestBuilder.kt"
        const val PROSE =
            "// every compact-only reshaping (withCompactDirective, if (opts.compact) ...) moved the prefix\n" +
                "val x = 1\n"
        val MAIN = mapOf(BUILDER_PATH to "val body = buildTurn(opts)\n")
        val TESTS = mapOf(TEST_PATH to "fun `${CompactionIsATurn.CANARY}`() {}")
        val FORBIDDEN_SAMPLES = listOf("withCompactDirective", "compactAwareSystem", "compactEffortPin")
    }
}
