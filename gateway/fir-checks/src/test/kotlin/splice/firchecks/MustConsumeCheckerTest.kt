// NEW (discipline L4): red/green proof of the wall. Drives K2JVMCompiler in-process against fixture
// files with -Xplugin=<this module's jar> (path from the system property the test task sets) and
// -cp=<test JVM classpath> (which already carries :core, so the fixtures resolve @MustConsume).
package splice.firchecks

import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import kotlin.io.path.writeText

class MustConsumeCheckerTest {

    @Test
    fun `a discarded MustConsume value is a compile error`() {
        assertFixtureRed("red/DiscardedMustConsume.kt.txt", "DiscardedMustConsume.kt")
    }

    @Test
    fun `a statement-position if branch tail is a compile error`() {
        assertFixtureRed("red/StatementIfBranchTail.kt.txt", "StatementIfBranchTail.kt")
    }

    @Test
    fun `a statement-position when branch tail is a compile error`() {
        assertFixtureRed("red/StatementWhenBranchTail.kt.txt", "StatementWhenBranchTail.kt")
    }

    @Test
    fun `a non-tail discard inside a consumed branch is a compile error`() {
        assertFixtureRed("red/MidBranchDiscard.kt.txt", "MidBranchDiscard.kt")
    }

    @Test
    fun `a bare safe-call statement discard is a compile error`() {
        assertFixtureRed("red/SafeCallStatementDiscard.kt.txt", "SafeCallStatementDiscard.kt")
    }

    @Test
    fun `a bare elvis statement discards its lhs call - compile error`() {
        assertFixtureRed("red/ElvisLhsStatementDiscard.kt.txt", "ElvisLhsStatementDiscard.kt")
    }

    @Test
    fun `a bare elvis statement discards its rhs call - compile error`() {
        assertFixtureRed("red/ElvisRhsStatementDiscard.kt.txt", "ElvisRhsStatementDiscard.kt")
    }

    @Test
    fun `a loop body tail discard is a compile error`() {
        assertFixtureRed("red/LoopBodyTailDiscard.kt.txt", "LoopBodyTailDiscard.kt")
    }

    @Test
    fun `a finally tail discard fires even under a consumed try`() {
        assertFixtureRed("red/FinallyTailDiscard.kt.txt", "FinallyTailDiscard.kt")
    }

    @Test
    fun `consumed values, consumed branch tails, safe-call and elvis, and lambda tails all compile clean`() {
        val result = compileFixture("green/ConsumedMustConsume.kt.txt", "ConsumedMustConsume.kt")
        assertEquals(ExitCode.OK, result.exitCode, result.output)
    }

    private fun assertFixtureRed(resource: String, fileName: String) {
        val result = compileFixture(resource, fileName)
        assertEquals(ExitCode.COMPILATION_ERROR, result.exitCode, result.output)
        assertTrue(
            result.output.contains("MustConsume"),
            "the diagnostic should name @MustConsume, was:\n${result.output}",
        )
    }

    private data class CompileResult(val exitCode: ExitCode, val output: String)

    private fun compileFixture(resource: String, fileName: String): CompileResult {
        val pluginJar = requireNotNull(System.getProperty("splice.firChecksPluginJar")) {
            "system property splice.firChecksPluginJar (set by the test task) is missing"
        }
        val source = requireNotNull(javaClass.getResourceAsStream("/must-consume-fixtures/$resource")) {
            "fixture resource not found on the test classpath: $resource"
        }.bufferedReader().use { it.readText() }

        val workDir = Files.createTempDirectory("must-consume-fixture")
        val srcFile = workDir.resolve(fileName)
        srcFile.writeText(source)
        val outDir = Files.createDirectories(workDir.resolve("out"))

        val buffer = ByteArrayOutputStream()
        val exit = PrintStream(buffer, true, Charsets.UTF_8).use { out ->
            K2JVMCompiler().exec(
                out,
                "-Xplugin=$pluginJar",
                "-cp", System.getProperty("java.class.path"),
                "-d", outDir.toString(),
                "-no-stdlib",
                "-no-reflect",
                srcFile.toString(),
            )
        }
        return CompileResult(exit, buffer.toString(Charsets.UTF_8))
    }
}
