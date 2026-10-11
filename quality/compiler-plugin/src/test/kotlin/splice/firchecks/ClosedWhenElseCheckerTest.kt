// NEW (discipline L4): red/green proof of the closed-`when` wall. The wall is not registered in the shipped plugin until the tree
// holds no violation, so this test packs its own throwaway plugin jar (the checker's classes plus a test-only registrar) and
// drives the same in-process compiler MustConsumeCheckerTest uses.
package splice.firchecks

import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.io.path.writeText

class ClosedWhenElseCheckerTest(@param:TempDir private val workDir: Path) {

    @Test
    fun `an else on an enum when is a compile error`() = assertRed("EnumElse")

    @Test
    fun `an else on a sealed class when is a compile error`() = assertRed("SealedElse")

    @Test
    fun `an else on a sealed interface when is a compile error`() = assertRed("SealedInterfaceElse")

    @Test
    fun `an else on a Boolean when is a compile error`() = assertRed("BooleanElse")

    @Test
    fun `an else on a nullable enum when is a compile error`() = assertRed("NullableEnumElse")

    @Test
    fun `an else on an enum when used as a statement is a compile error`() = assertRed("StatementEnumElse")

    @Test
    fun `exhaustive closed whens and the open whens that need an else all compile clean`() {
        val (exit, output) = compile("green/ClosedAndOpenWhens.kt.txt", "ClosedAndOpenWhens.kt")
        assertEquals(ExitCode.OK, exit, output)
    }

    private fun assertRed(name: String) {
        val (exit, output) = compile("red/$name.kt.txt", "$name.kt")
        assertEquals(ExitCode.COMPILATION_ERROR, exit, output)
        val diagnostic = output.contains("closedWhen: this `when`")
        assertTrue(diagnostic, "the diagnostic should be the closedWhen error, was:\n$output")
    }

    /** The checker's own classes and the test registrar, in one jar the compiler loads as a plugin. */
    private fun throwawayPlugin(): Path {
        val jar = workDir.resolve("closed-when-plugin.jar")
        JarOutputStream(Files.newOutputStream(jar)).use { out ->
            listOf(ClosedWhenElseChecker::class.java, TestClosedWhenRegistrar::class.java).forEach { anchor ->
                classFiles(anchor).forEach { (name, bytes) -> entry(out, name, bytes) }
            }
            val service = "META-INF/services/org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar"
            entry(out, service, "splice.firchecks.TestClosedWhenRegistrar\n".toByteArray())
        }
        return jar
    }

    /** Every class file of ours (ClosedWhen*, TestClosedWhen*) in the classes directory [anchor] was loaded from. */
    private fun classFiles(anchor: Class<*>): List<Pair<String, ByteArray>> {
        val root = Path.of(anchor.protectionDomain.codeSource.location.toURI())
        val ours = Files.walk(root).use { paths ->
            paths.filter { it.toString().endsWith(".class") && isOurs(it.fileName.toString()) }.toList()
        }
        return ours.map { root.relativize(it).toString().replace('\\', '/') to Files.readAllBytes(it) }
    }

    private fun isOurs(file: String): Boolean = file.startsWith("ClosedWhen") || file.startsWith("TestClosedWhen")

    private fun entry(out: JarOutputStream, name: String, bytes: ByteArray) {
        out.putNextEntry(JarEntry(name))
        out.write(bytes)
        out.closeEntry()
    }

    private fun compile(resource: String, fileName: String): Pair<ExitCode, String> {
        val source = requireNotNull(javaClass.getResourceAsStream("/closed-when-fixtures/$resource")) {
            "fixture resource not found on the test classpath: $resource"
        }.bufferedReader().use { it.readText() }
        val srcFile = workDir.resolve(fileName)
        srcFile.writeText(source)
        val outDir = Files.createDirectories(workDir.resolve("out"))
        val buffer = ByteArrayOutputStream()
        val exit = PrintStream(buffer, true, Charsets.UTF_8).use { out ->
            K2JVMCompiler().exec(
                out,
                "-Xplugin=${throwawayPlugin()}",
                "-cp", System.getProperty("java.class.path"),
                "-d", outDir.toString(),
                "-no-stdlib",
                "-no-reflect",
                srcFile.toString(),
            )
        }
        return exit to buffer.toString(Charsets.UTF_8)
    }
}
