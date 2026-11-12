// NEW (V4-92, v3): the reports under real incremental compilation. A report reads classes its source never names: the
// bound of a type parameter two star projections away, and an alias in that bound. SurfaceLookups records each one as
// a lookup of the source, so changing it recompiles the source and rewrites its report. Each test drives the Build
// Tools API's snapshot-based incremental compilation, the engine KGP runs, through a first build and one edit, and the
// reports it ends with must be the ones a whole compile of the new text writes. Every file the test writes is under its
// TempDir.
@file:OptIn(ExperimentalBuildToolsApi::class)

package splice.firchecks

import org.jetbrains.kotlin.buildtools.api.BuildOperation
import org.jetbrains.kotlin.buildtools.api.CompilationResult
import org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi
import org.jetbrains.kotlin.buildtools.api.KotlinLogger
import org.jetbrains.kotlin.buildtools.api.KotlinToolchains
import org.jetbrains.kotlin.buildtools.api.SourcesChanges
import org.jetbrains.kotlin.buildtools.api.arguments.CommonCompilerArguments
import org.jetbrains.kotlin.buildtools.api.arguments.CompilerPlugin
import org.jetbrains.kotlin.buildtools.api.arguments.CompilerPluginOption
import org.jetbrains.kotlin.buildtools.api.jvm.JvmIncrementalCompilationConfiguration
import org.jetbrains.kotlin.buildtools.api.jvm.JvmPlatformToolchain
import org.jetbrains.kotlin.buildtools.api.jvm.operations.JvmCompilationOperation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

class PublicSurfaceIncrementalTest(@param:TempDir private val workDir: Path) {
    private val toolchains = KotlinToolchains.loadImplementation(PublicSurfaceIncrementalTest::class.java.classLoader)
    private val jvm = toolchains.getToolchain(JvmPlatformToolchain::class.java)
    private val stdlib: Path = Path.of(Unit::class.java.protectionDomain.codeSource.location.toURI())
    private val stdlibSnapshot: Path = workDir.resolve("kotlin-stdlib.snapshot")
    private val shipped: Path = Path.of(
        requireNotNull(System.getProperty("splice.firChecksPluginJar")) {
            "system property splice.firChecksPluginJar (set by the test task) is missing"
        },
    )

    init {
        val snapshot = execute(jvm.classpathSnapshottingOperationBuilder(stdlib).build(), CollectingLogger())
        snapshot.saveSnapshot(stdlibSnapshot)
    }

    @Test
    fun `a bound two star projections away is a lookup - changing it rewrites the report that reads it`() {
        assertRecorded(STAR_ROUTE, MID, "package fix.lib\n\npublic open class Mid<T : New>")
    }

    @Test
    fun `retargeting an alias in that bound rewrites the report that reads it`() {
        assertRecorded(ALIAS_ROUTE, BOUND, "package fix.lib\n\npublic typealias Bound = New")
    }

    /** Builds [sources], rewrites [edited] to [text], and builds again incrementally. Types.kt reads nothing that
     *  changes, so its report staying untouched shows that the second compile was incremental. */
    private fun assertRecorded(sources: Map<String, String>, edited: String, text: String) {
        val whole = Module(workDir.resolve("whole")).apply { write(sources + (edited to text)) }.compile()
        assertTrue(whole.getValue(USER_REPORT).contains(USER_NEW), whole.getValue(USER_REPORT))
        val incremental = rebuild(sources, edited, text)
        assertTrue(USER_REPORT in incremental.rewritten, "User.kt was recompiled: ${incremental.rewritten}")
        assertFalse(TYPES_REPORT in incremental.rewritten, "Types.kt was not: ${incremental.rewritten}")
        assertEquals(whole, incremental.reports, "the incremental set equals a whole compile of the new text")
    }

    private data class Rebuild(val rewritten: Set<String>, val reports: Map<String, String>)

    /** A first build of [sources], every report aged, [edited] rewritten to [text], and the incremental build that
     *  edit alone starts. */
    private fun rebuild(sources: Map<String, String>, edited: String, text: String): Rebuild {
        val module = Module(workDir.resolve("incremental"))
        module.write(sources)
        module.compile(SourcesChanges.Unknown)
        module.age()
        module.write(mapOf(edited to text))
        val reports = module.compile(SourcesChanges.Known(listOf(module.file(edited)), emptyList()))
        return Rebuild(module.rewritten(), reports)
    }

    private fun <R> execute(operation: BuildOperation<R>, logger: KotlinLogger): R =
        toolchains.createBuildSession().use { session ->
            session.executeOperation(operation, toolchains.createInProcessExecutionPolicy(), logger)
        }

    /** One fixture module under [root]: sources and reports under lib/, classes, and the incremental caches. */
    private inner class Module(private val root: Path) {
        private val dir = root.resolve("lib")
        private val reportDir = dir.resolve("build/reports").toFile()
        private val paths = sortedSetOf<String>()

        fun write(sources: Map<String, String>) {
            for ((path, text) in sources) {
                val source = dir.resolve(path)
                Files.createDirectories(source.parent)
                Files.writeString(source, "$text\n")
                paths += path
            }
        }

        fun file(path: String): File = dir.resolve(path).toFile()

        /** A whole compile, or with [changes] the snapshot-based incremental compile KGP runs. Returns every report,
         *  after checking that each source has one and nothing else does. */
        fun compile(changes: SourcesChanges? = null): Map<String, String> {
            val sources = paths.map { dir.resolve(it) }
            val builder = jvm.jvmCompilationOperationBuilder(sources, Files.createDirectories(root.resolve("classes")))
            builder.compilerArguments.applyArgumentStrings(ARGUMENTS + listOf("-classpath", stdlib.toString()))
            builder.compilerArguments.set(CommonCompilerArguments.COMPILER_PLUGINS, listOf(compilerPlugin()))
            if (changes != null) {
                builder.set(JvmCompilationOperation.INCREMENTAL_COMPILATION, incremental(builder, changes))
            }
            val log = CollectingLogger()
            assertEquals(CompilationResult.COMPILATION_SUCCESS, execute(builder.build(), log), log.text())
            return reports()
        }

        /** Marks every report as older than the next compile: its modification time goes to the epoch. */
        fun age() {
            reportFiles().forEach { check(it.setLastModified(0)) { "$it: its modification time could not be set" } }
        }

        /** The reports the last compile wrote, told apart by the modification time [age] reset. */
        fun rewritten(): Set<String> = reportFiles().filter { it.lastModified() != 0L }.mapTo(sortedSetOf(), ::key)

        private fun reports(): Map<String, String> {
            val reports = reportFiles().associate { key(it) to it.readText() }
            assertEquals(paths.mapTo(sortedSetOf()) { "$it.json" }, reports.keys.toSortedSet(), "one report per source")
            return reports
        }

        private fun reportFiles(): List<File> = reportDir.walkTopDown().filter { it.isFile }.toList()

        private fun key(report: File): String = report.relativeTo(reportDir).invariantSeparatorsPath

        private fun compilerPlugin(): CompilerPlugin = CompilerPlugin(
            PLUGIN_ID,
            listOf(shipped),
            listOf(
                CompilerPluginOption(reportDirOption.optionName, reportDir.path),
                CompilerPluginOption(sourceRootOption.optionName, dir.toString()),
            ),
            emptySet(),
        )

        private fun incremental(
            builder: JvmCompilationOperation.Builder,
            changes: SourcesChanges,
        ): JvmIncrementalCompilationConfiguration = builder.snapshotBasedIcConfigurationBuilder(
            Files.createDirectories(root.resolve("ic")),
            changes,
            listOf(stdlibSnapshot),
            root.resolve("ic/shrunk-classpath-snapshot.bin"),
        ).build()
    }

    /** Keeps what the compiler says, so a failed compile shows why. */
    private class CollectingLogger : KotlinLogger {
        private val lines = StringBuilder()

        override val isDebugEnabled: Boolean = false

        override fun error(msg: String, throwable: Throwable?) {
            lines.appendLine("e: $msg${throwable?.let { " ($it)" }.orEmpty()}")
        }

        override fun warn(msg: String, throwable: Throwable?) {
            lines.appendLine("w: $msg")
        }

        override fun info(msg: String) {
            lines.appendLine("i: $msg")
        }

        override fun debug(msg: String) = Unit

        override fun lifecycle(msg: String) {
            lines.appendLine(msg)
        }

        fun text(): String = lines.toString()
    }
}

private const val TYPES = "src/main/kotlin/Types.kt"
private const val BOUND = "src/main/kotlin/Bound.kt"
private const val MID = "src/main/kotlin/Mid.kt"
private const val USER = "src/main/kotlin/User.kt"
private const val TYPES_REPORT = "$TYPES.json"
private const val USER_REPORT = "$USER.json"
private const val USER_NEW = "\"fix.lib.User\": [\"fix.lib.Box\", \"fix.lib.Mid\", \"fix.lib.New\", \"kotlin.Any\"]"

/** The arguments PublicSurfaceReportTest compiles with; the classpath is the standard library alone. */
private val ARGUMENTS: List<String> =
    listOf("-Xexplicit-api=strict", "-Werror", "-no-stdlib", "-no-reflect", "-module-name", "lib")

/** User names only Box. Box's bound names Mid, and Mid's bound names Old: two star projections from User.
 *  Old, New and Mid are open, because a final upper bound is a warning and the compile runs with -Werror. */
private val STAR_ROUTE: Map<String, String> = mapOf(
    TYPES to "package fix.lib\n\npublic open class Old\n\npublic open class New",
    MID to "package fix.lib\n\npublic open class Mid<T : Old>",
    "src/main/kotlin/Box.kt" to "package fix.lib\n\npublic class Box<T : Mid<*>>",
    USER to "package fix.lib\n\npublic class User {\n    public fun read(): Box<*>? = null\n}",
)

/** The same chain, with Mid's bound spelled through an alias that Bound.kt declares. */
private val ALIAS_ROUTE: Map<String, String> = STAR_ROUTE + mapOf(
    BOUND to "package fix.lib\n\npublic typealias Bound = Old",
    MID to "package fix.lib\n\npublic open class Mid<T : Bound>",
)
