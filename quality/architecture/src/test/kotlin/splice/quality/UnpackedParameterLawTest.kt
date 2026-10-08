// NEW (2026-10-07): the parameter-in-a-bag escape, closed. A class that receives a bundle through its
// primary constructor and copies its members back into its own fields — `private val failedHeads = probes.failedHeads`
// — has moved the constructor's width without moving its knowledge: the bundle is named, the members are
// still unpacked, and the class still depends on every one of them. ConstructorWidthLawTest measures the
// width and names this blind spot (its NOT CAUGHT section); this law is the instrument for it.
//
// THE RULE. A class whose body declares two or more STORED copies, where a stored copy is a property or field
// whose initializer is a member of one of its own primary constructor parameters (`val|var x [: T] = p.x`), and `x`
// is the same name, is a violation. One is allowed: a single copy is a convenience, two is a bag being unpacked.
// A getter is a view, not a copy: `val x: T get() = p.x` reads through to the owner and holds nothing, so it is
// not counted. The two shapes differ by the accessor between the type and the `=`, and only the stored form matches.
//
// NO ALLOWLIST. A violation is fixed in the code, never listed. The law reads every product `src/main` file the
// project map yields, and it counts zero or it fails by name. A fixture test proves the detector can fail, so
// the real-tree test passing means the scan ran, not that it found nothing it could see.
//
// SCOPE. The same file walk as ConstructorWidthLawTest, so a file the width law reads is a file this law reads.
package splice.quality

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

internal object UnpackedParameters {
    /** `class Name(` with the opening paren last in the match. */
    private val CLASS_HEAD = Regex("""\bclass\s+([A-Za-z_][A-Za-z0-9_]*)[^({\n]*\(""")

    /** A body line `val|var x = p.x` (with an optional type and visibility): a STORED copy, the one this law counts.
     *  A getter is a view, so its accessor sits between the type and the `=`, where the type group refuses it. */
    private val UNPACK = Regex(
        """^\s*(?:(?:private|internal|public|protected)\s+)?(?:val|var)\s+([A-Za-z_]\w*)(?:\s*:(?:(?!\bget\s*\()[^=\n])+)?\s*=\s*([A-Za-z_]\w*)\.([A-Za-z_]\w*)\s*$""",
        RegexOption.MULTILINE,
    )

    /** The name a primary constructor parameter declares, past its annotations, visibility and val/var. */
    private val PARAM_NAME = Regex(
        """^\s*(?:@[\w.]+(?:\([^)]*\))?\s*)*(?:(?:private|internal|public|protected)\s+)?(?:val\s+|var\s+)?([A-Za-z_]\w*)\s*:""",
    )

    /** Between a primary constructor's `)` and its body `{` only a supertype clause may sit. */
    private val DECLARATION_KEYWORD = Regex("""\b(class|fun|val|var|object|interface|enum)\b""")

    data class Unpacking(val rel: String, val line: Int, val klass: String, val fields: List<String>) {
        override fun toString(): String =
            "$rel:$line: $klass copies ${fields.joinToString()} out of its own constructor parameters — " +
                "pass the collaborator, or the component that owns those members, not an unpacked copy"
    }

    /** Every class in [text] whose body copies two or more of its primary constructor parameters. */
    fun scan(rel: String, text: String): List<Unpacking> {
        val source = KotlinText.blankComments(text)
        return CLASS_HEAD.findAll(source).mapNotNull { unpackingOf(rel, source, it) }.toList()
    }

    /** The copies one class head makes of its own constructor parameters, or null when it makes fewer than two. */
    private fun unpackingOf(rel: String, source: String, head: MatchResult): Unpacking? {
        val parens = KotlinText.balancedSpan(source, head.range.last, '(', ')') ?: return null
        val params = constructorParams(source.substring(parens.bodyStart, parens.closerAt))
        val copied = copiedMembers(bodyAfter(source, parens.closerAt + 1), params)
        if (copied.size < 2) return null
        val line = source.substring(0, head.range.first).count { it == '\n' } + 1
        return Unpacking(rel, line, head.groupValues[1], copied)
    }

    private fun constructorParams(parameters: String): List<String> =
        ConstructorWidth.splitParams(parameters).mapNotNull { PARAM_NAME.find(it)?.groupValues?.get(1) }

    /** The members stored as copies of one of [params]: `val|var x = p.x`, where `p` is a parameter and `x` its own name. */
    private fun copiedMembers(members: String, params: List<String>): List<String> =
        UNPACK.findAll(members)
            .filter { it.groupValues[2] in params && it.groupValues[3] == it.groupValues[1] }
            .map { it.groupValues[1] }
            .toList()

    /** The class body that opens after [gapStart], or "" when the gap holds more than a supertype clause. */
    private fun bodyAfter(source: String, gapStart: Int): String {
        val braceAt = source.indexOf('{', gapStart)
        if (braceAt < 0 || !isSupertypeGap(source.substring(gapStart, braceAt))) return ""
        val body = KotlinText.balancedSpan(source, braceAt, '{', '}') ?: return ""
        return source.substring(body.bodyStart, body.closerAt)
    }

    /** Only a supertype clause sits here: no declaration keyword, and no more than three lines. */
    private fun isSupertypeGap(gap: String): Boolean =
        !DECLARATION_KEYWORD.containsMatchIn(gap) && gap.count { it == '\n' } <= 3
}

internal class UnpackedParameterLawTest {
    private val map = ProjectMap.fromSystemProperties()

    private fun relative(file: File): String = file.relativeTo(map.root).invariantSeparatorsPath

    @Test
    fun `no class copies two or more of its constructor parameters into fields of its own`() {
        val files = KotlinText.kotlinFiles(map)
        assertTrue(files.size > 10) {
            "the map yielded ${files.size} production file(s) — the walk is broken, and a law that reads no " +
                "files passes vacuously."
        }
        val found = files.flatMap { UnpackedParameters.scan(relative(it), it.readText()) }
        assertTrue(found.isEmpty()) {
            "${found.size} class(es) unpack their constructor parameters:\n" + found.joinToString("\n")
        }
    }

    @Test
    fun `INVALID - stored copies of constructor parameters are flagged, the bag ControlServer unpacked at 3f2162e48`() {
        val text = """
            public class ControlServer(
                private val port: Int,
                probes: ControlHealthProbes = ControlHealthProbes(),
                runtime: ControlRuntime = ControlRuntime(),
            ) {
                private val failedHeads: FailedHeads = probes.failedHeads
                private val topologyStale: TopologyStale = probes.topologyStale
                private val launchService: LaunchService? = runtime.launchService
            }
        """.trimIndent()
        val found = UnpackedParameters.scan("fixture/ControlServer.kt", text)
        assertEquals(1, found.size, found.joinToString())
        assertEquals("ControlServer", found.single().klass)
        assertEquals(listOf("failedHeads", "topologyStale", "launchService"), found.single().fields)
    }

    @Test
    fun `VALID - a get() forwarder is a view, not a copy, and is not flagged`() {
        val text = """
            class Usage(
                private val history: History,
            ) {
                val absorbed: Long get() = history.absorbed
                val evicted: Long get() = history.evicted
            }
        """.trimIndent()
        assertTrue(UnpackedParameters.scan("fixture/Usage.kt", text).isEmpty())
    }

    @Test
    fun `a var copy is a stored copy and is counted like a val`() {
        val text = """
            class Holder(
                daemon: DaemonEnvironment,
            ) {
                private var statePaths = daemon.statePaths
                private var config = daemon.config
            }
        """.trimIndent()
        assertEquals(listOf("statePaths", "config"), UnpackedParameters.scan("fixture/Holder.kt", text).single().fields)
    }

    @Test
    fun `a single copy is not a bag and is not flagged`() {
        val text = """
            class Holder(
                daemon: DaemonEnvironment,
                private val port: Int,
            ) {
                private val statePaths = daemon.statePaths
                private val other = port.toString()
            }
        """.trimIndent()
        assertTrue(UnpackedParameters.scan("fixture/Holder.kt", text).isEmpty())
    }

    @Test
    fun `a copy from something that is not a constructor parameter is not counted`() {
        val text = """
            class Service(
                private val port: Int,
            ) {
                private val a = registry.a
                private val b = registry.b
            }
        """.trimIndent()
        assertTrue(UnpackedParameters.scan("fixture/Service.kt", text).isEmpty())
    }
}
