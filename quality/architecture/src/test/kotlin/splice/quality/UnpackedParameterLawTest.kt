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
// THREAT MODEL. The law catches a member copy in every spelling the Kotlin parser reads as `param.member`: the
// property is parsed with the compiler's own PSI, parentheses are unwrapped as syntax, and a copy is a dot-qualified
// expression whose receiver is a bare name that is a primary-constructor parameter and whose selector is a bare name.
// A call, a safe call, a lambda or any other construct is not a copy, so `p(a).x` is not one. Rewrites that route
// through other constructs, such as `with(p) { a }`, `p.let { it.a }` or a property reference, are out of scope here;
// review covers those.
//
// BY SHAPE, NOT BY TEXT. Konsist reads the declarations: a class, its primary constructor's parameters, and the
// properties the class itself declares, each with its initializer. Modifiers (`override`, `private`, `open`) play no
// part and a class header of any length is one declaration. The initializer is then parsed, never matched as text.
//
// NO ALLOWLIST. A violation is fixed in the code, never listed. The law reads every product `src/main` file the
// project map yields, and it counts zero or it fails by name. A fixture test proves the detector can fail, so
// the real-tree test passing means the scan ran, not that it found nothing it could see.
//
// SCOPE. The same file walk as ConstructorWidthLawTest, so a file the width law reads is a file this law reads.
package splice.quality

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.declaration.KoClassDeclaration
import com.lemonappdev.konsist.api.declaration.KoPropertyDeclaration
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtParenthesizedExpression
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

internal object UnpackedParameters {
    /** The compiler's parser, started once: initializers are parsed, never matched as text. */
    private val psi: KtPsiFactory by lazy {
        val configuration = CompilerConfiguration().apply {
            put(CommonConfigurationKeys.MESSAGE_COLLECTOR_KEY, MessageCollector.NONE)
        }
        val environment = KotlinCoreEnvironment.createForProduction(
            Disposer.newDisposable("unpack-law"),
            configuration,
            EnvironmentConfigFiles.JVM_CONFIG_FILES,
        )
        KtPsiFactory(environment.project, markGenerated = false)
    }

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
            .filter { property -> storedCopyOf(property, params) == property.name }
            .map { it.name }
        if (copied.size < 2) return null
        return Unpacking(rel, lineOf(klass), klass.name, copied)
    }

    /** The member name when the property's initializer parses as `p.member` and `p` is one of [params]. */
    private fun storedCopyOf(property: KoPropertyDeclaration, params: Set<String>): String? {
        val initializer = psi.createDeclaration<KtProperty>(property.text).initializer ?: return null
        val copy = unwrapped(initializer) as? KtDotQualifiedExpression
        val receiver = copy?.let { unwrapped(it.receiverExpression) } as? KtNameReferenceExpression
        val member = copy?.selectorExpression as? KtNameReferenceExpression
        return member?.getReferencedName()?.takeIf { receiver?.getReferencedName() in params }
    }

    /** [expression] with every wrapping pair of parentheses taken off, as syntax. */
    private fun unwrapped(expression: KtExpression): KtExpression {
        var inner = expression
        while (inner is KtParenthesizedExpression) inner = inner.expression ?: return inner
        return inner
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
    fun `INVALID - a spaced dot, a parenthesised receiver and an inline comment are the same stored copy`(
        @TempDir dir: File,
    ) {
        val found = scan(
            dir,
            """
            class Holder(daemon: DaemonEnvironment) {
                private val statePaths = daemon . statePaths
                private val config = (daemon).config
                private val limits = daemon/*copy*/.limits
            }
            """,
        )
        assertEquals(listOf("statePaths", "config", "limits"), found.single().fields)
    }

    @Test
    fun `INVALID - a nested block comment between receiver and member is still a stored copy`(@TempDir dir: File) {
        val found = scan(
            dir,
            """
            class Holder(daemon: DaemonEnvironment) {
                private val statePaths = daemon/*outer /*inner*/ outer*/.statePaths
                private val config = daemon /* a */ . /* b */ config
            }
            """,
        )
        assertEquals(listOf("statePaths", "config"), found.single().fields)
    }

    @Test
    fun `VALID - a call whose argument is a parameter's name is not a copy of that parameter`(@TempDir dir: File) {
        val found = scan(
            dir,
            """
            class Holder(pa: Source, p: (Int) -> Source) {
                private val statePaths = p(a).statePaths
                private val config = p(a).config
            }
            """,
        )
        assertTrue(found.isEmpty(), found.joinToString())
    }

    @Test
    fun `an unwrapped expression that is not a bare member stays out`(@TempDir dir: File) {
        val found = scan(
            dir,
            """
            class Holder(daemon: DaemonEnvironment) {
                private val statePaths = (daemon).statePaths.toString()
                private val config = daemon.config.copy()
                private val limits = daemon.limits ?: other.limits
            }
            """,
        )
        assertTrue(found.isEmpty(), found.joinToString())
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
