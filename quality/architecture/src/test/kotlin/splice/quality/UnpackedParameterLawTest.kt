// NEW (2026-10-07): the parameter-in-a-bag escape, closed. A class that receives a bundle through its
// primary constructor and copies its members back into its own fields — `private val failedHeads = probes.failedHeads`
// — has moved the constructor's width without moving its knowledge: the bundle is named, the members are
// still unpacked, and the class still depends on every one of them. ConstructorWidthLawTest measures the
// width and names this blind spot (its NOT CAUGHT section); this law is the instrument for it.
//
// THE RULE. A class whose body declares two or more STORED copies, where a stored copy is a class-level property
// whose initializer is a member of one of its own primary constructor parameters (`x = p.x`, `x = (p.x)`), and `x`
// is the same name, is a violation. One is allowed: a single copy is a convenience, two is a bag being unpacked.
// A getter is a view, not a copy: `val x: T get() = p.x` reads through to the owner and holds nothing, so it has no
// initializer and is not counted. Locals inside a method are not fields of the class and are never counted.
//
// BY SHAPE, NOT BY TEXT. Konsist reads the declarations: a class, its primary constructor's parameters, and the
// properties the class itself declares, each with its initializer. Modifiers (`override`, `private`, `open`) play no
// part, a class header of any length is one declaration, and a wrapped initializer is unwrapped to its expression.
//
// NO ALLOWLIST. A violation is fixed in the code, never listed. The law reads every product `src/main` file the
// project map yields, and it counts zero or it fails by name. A fixture test proves the detector can fail, so
// the real-tree test passing means the scan ran, not that it found nothing it could see.
//
// SCOPE. The same file walk as ConstructorWidthLawTest, so a file the width law reads is a file this law reads.
package splice.quality

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.declaration.KoClassDeclaration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

internal object UnpackedParameters {
    /** An initializer that is a bare member of one name: `p.x`. */
    private val MEMBER = Regex("""^([A-Za-z_]\w*)\.([A-Za-z_]\w*)$""")

    data class Unpacking(val rel: String, val line: Int, val klass: String, val fields: List<String>) {
        override fun toString(): String =
            "$rel:$line: $klass copies ${fields.joinToString()} out of its own constructor parameters — " +
                "pass the collaborator, or the component that owns those members, not an unpacked copy"
    }

    /** Every class among [classes] that copies two or more of its primary constructor parameters. */
    fun scan(rel: (KoClassDeclaration) -> String, classes: List<KoClassDeclaration>): List<Unpacking> =
        classes.mapNotNull { unpackingOf(rel(it), it) }

    private fun unpackingOf(rel: String, klass: KoClassDeclaration): Unpacking? {
        val params = klass.primaryConstructor?.parameters?.map { it.name }.orEmpty().toSet()
        if (params.isEmpty()) return null
        val copied = klass.properties(includeNested = false)
            .filter { !it.isConstructorDefined }
            .filter { property -> storedCopyOf(property.value, params) == property.name }
            .map { it.name }
        if (copied.size < 2) return null
        return Unpacking(rel, lineOf(klass), klass.name, copied)
    }

    /** The member name when [initializer] is `p.member` (parentheses unwrapped) and `p` is one of [params]. */
    private fun storedCopyOf(initializer: String?, params: Set<String>): String? {
        val match = MEMBER.matchEntire(unwrapParentheses(initializer.orEmpty())) ?: return null
        return match.groupValues[2].takeIf { match.groupValues[1] in params }
    }

    /** Strips parentheses that wrap the WHOLE expression: `((p.x))` is `p.x`, `(a).b` is untouched. */
    private fun unwrapParentheses(expression: String): String {
        var text = expression.trim()
        while (isWrapped(text)) text = text.substring(1, text.lastIndex).trim()
        return text
    }

    private fun isWrapped(text: String): Boolean = text.startsWith("(") && closerOfFirst(text) == text.lastIndex

    private fun closerOfFirst(text: String): Int {
        var depth = 0
        for ((index, char) in text.withIndex()) {
            if (char == '(') depth++
            if (char == ')' && --depth == 0) return index
        }
        return -1
    }

    /** The line a class starts on, from the `path:line:column` location Konsist reports. */
    private fun lineOf(klass: KoClassDeclaration): Int =
        klass.location.split(':').let { it[it.size - 2].toInt() }
}

internal class UnpackedParameterLawTest {
    private val map = ProjectMap.fromSystemProperties()

    private fun relativeOf(klass: KoClassDeclaration): String =
        File(klass.containingFile.path).relativeTo(map.root).invariantSeparatorsPath

    @Test
    fun `no class copies two or more of its constructor parameters into fields of its own`() {
        val roots = map.modules.sorted().filter { File(map.dir(it), "src/main").isDirectory }
        assertTrue(roots.size > 10) {
            "the map yielded ${roots.size} production source root(s) — the walk is broken, and a law that reads no " +
                "files passes vacuously."
        }
        val classes = roots.flatMap {
            Konsist.scopeFromDirectory("${map.relativeDir(it)}/src/main").classes(includeNested = true)
        }
        assertTrue(classes.size > 100) {
            "the scan read ${classes.size} class(es); a law that reads none passes vacuously."
        }
        val found = UnpackedParameters.scan(::relativeOf, classes)
        assertTrue(found.isEmpty()) {
            "${found.size} class(es) unpack their constructor parameters:\n" + found.joinToString("\n")
        }
    }

    @Test
    fun `INVALID - stored copies of constructor parameters are flagged, the bag ControlServer unpacked at 3f2162e48`(
        @TempDir dir: File,
    ) {
        val found = scan(
            dir,
            """
            public class ControlServer(
                private val port: Int,
                probes: ControlHealthProbes = ControlHealthProbes(),
                runtime: ControlRuntime = ControlRuntime(),
            ) {
                private val failedHeads: FailedHeads = probes.failedHeads
                private val topologyStale: TopologyStale = probes.topologyStale
                private val launchService: LaunchService? = runtime.launchService
            }
            """,
        )
        assertEquals(1, found.size, found.joinToString())
        assertEquals("ControlServer", found.single().klass)
        assertEquals(listOf("failedHeads", "topologyStale", "launchService"), found.single().fields)
        assertEquals(3, found.single().line)
    }

    @Test
    fun `INVALID - a parenthesised initializer is still a stored copy`(@TempDir dir: File) {
        val found = scan(
            dir,
            """
            class Holder(daemon: DaemonEnvironment) {
                private val statePaths = (daemon.statePaths)
                private val config = ((daemon.config))
            }
            """,
        )
        assertEquals(listOf("statePaths", "config"), found.single().fields)
    }

    @Test
    fun `INVALID - an override or open property is still a stored copy`(@TempDir dir: File) {
        val found = scan(
            dir,
            """
            class Holder(daemon: DaemonEnvironment) : Base() {
                override val statePaths = daemon.statePaths
                open val config = daemon.config
            }
            """,
        )
        assertEquals(listOf("statePaths", "config"), found.single().fields)
    }

    @Test
    fun `INVALID - a class whose supertype clause spans many lines is still read`(@TempDir dir: File) {
        val found = scan(
            dir,
            """
            class Holder(
                daemon: DaemonEnvironment,
            ) : Base(),
                FirstPort,
                SecondPort,
                ThirdPort,
                FourthPort {
                private val statePaths = daemon.statePaths
                private val config = daemon.config
            }
            """,
        )
        assertEquals("Holder", found.single().klass)
    }

    @Test
    fun `VALID - method-local copies are not fields of the class`(@TempDir dir: File) {
        val found = scan(
            dir,
            """
            class Holder(daemon: DaemonEnvironment) {
                fun render(): String {
                    val statePaths = daemon.statePaths
                    val config = daemon.config
                    return "${'$'}statePaths ${'$'}config"
                }
            }
            """,
        )
        assertTrue(found.isEmpty(), found.joinToString())
    }

    @Test
    fun `VALID - a get() forwarder is a view, not a copy, and is not flagged`(@TempDir dir: File) {
        val found = scan(
            dir,
            """
            class Usage(
                private val history: History,
            ) {
                val absorbed: Long get() = history.absorbed
                val evicted: Long get() = history.evicted
            }
            """,
        )
        assertTrue(found.isEmpty(), found.joinToString())
    }

    @Test
    fun `a var copy is a stored copy and is counted like a val`(@TempDir dir: File) {
        val found = scan(
            dir,
            """
            class Holder(
                daemon: DaemonEnvironment,
            ) {
                private var statePaths = daemon.statePaths
                private var config = daemon.config
            }
            """,
        )
        assertEquals(listOf("statePaths", "config"), found.single().fields)
    }

    @Test
    fun `a single copy is not a bag and is not flagged`(@TempDir dir: File) {
        val found = scan(
            dir,
            """
            class Holder(
                daemon: DaemonEnvironment,
                private val port: Int,
            ) {
                private val statePaths = daemon.statePaths
                private val other = port.toString()
            }
            """,
        )
        assertTrue(found.isEmpty(), found.joinToString())
    }

    @Test
    fun `a copy from something that is not a constructor parameter is not counted`(@TempDir dir: File) {
        val found = scan(
            dir,
            """
            class Service(
                private val port: Int,
            ) {
                private val a = registry.a
                private val b = registry.b
            }
            """,
        )
        assertTrue(found.isEmpty(), found.joinToString())
    }

    private fun scan(dir: File, source: String): List<UnpackedParameters.Unpacking> {
        File(dir, "Fixture.kt").writeText("package fixture\n\n" + source.trimIndent() + "\n")
        val classes = Konsist.scopeFromExternalDirectory(dir.path).classes(includeNested = true)
        return UnpackedParameters.scan({ it.name }, classes)
    }
}
