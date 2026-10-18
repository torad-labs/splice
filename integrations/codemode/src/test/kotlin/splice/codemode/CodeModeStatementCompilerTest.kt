package splice.codemode

import com.oracle.js.parser.ErrorManager
import com.oracle.js.parser.Parser
import com.oracle.js.parser.ScriptEnvironment
import com.oracle.js.parser.Source
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// why: this chain previously exhausted the compiler's JVM stack even though GraalJS parses it iteratively.
private const val DEEP_MEMBER_CHAIN = 50_000

// why: a quarter of the JVM's default 1 MB thread stack; the boundary parse of one statement needs a few kilobytes.
private const val SMALL_STACK_BYTES = 256L * 1024

class CodeModeStatementCompilerTest {
    /** Deep valid source must not depend on JVM stack-overflow recovery to finish compilation. */
    @Test
    fun `a deep member chain compiles without exhausting a small JVM stack`() {
        val source = "const v = " + "a.".repeat(DEEP_MEMBER_CHAIN) + "a;"
        val parser = CodeModeStatementParser(CodeModeStatementSyntax { true })
        parser.append(source, finished = true, error = null)
        var parsed: Result<Any?>? = null
        var read: Result<StatementInput?>? = null
        val reader = Thread(
            null,
            {
                parsed = runCatching {
                    Parser(
                        ScriptEnvironment.builder().strict(true).build(),
                        Source.sourceFor("chain", source),
                        object : ErrorManager() {},
                    ).parseFunctionBody(false, true)
                }
                read = runCatching { parser.next() }
            },
            "small-stack",
            SMALL_STACK_BYTES,
        )
        reader.start()
        reader.join()
        assertTrue(checkNotNull(parsed).getOrNull() != null, "the parse fits this stack")
        val program = assertInstanceOf(StatementInput.Program::class.java, checkNotNull(read).getOrThrow())
        assertEquals(listOf("a"), program.compiled.reads)
        assertEquals(
            "const v = ${program.compiled.scopeName}.values[\"a\"]" + ".a".repeat(DEEP_MEMBER_CHAIN) + ";",
            program.compiled.source,
        )
        assertNull(parser.overflow, "compilation must not exhaust or retire the worker")
    }
}
