package splice.codemode

import com.oracle.js.parser.ErrorManager
import com.oracle.js.parser.Parser
import com.oracle.js.parser.ScriptEnvironment
import com.oracle.js.parser.Source
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// why: GraalJS parses a member chain in a loop and the compiler walks it recursively, so on the stack below this chain
// leaves the compiler about five bytes a level, which no JVM frame fits; the overflow is always the compiler's.
private const val COMPILER_OVERFLOW_CHAIN = 200_000

// why: keep the JVM's normal 1 MB stack, rather than exercising its native minimum-stack guard.
// The deeper chain retains the same five-byte-per-level bound that forces the compiler to overflow.
private const val COMPILER_STACK_BYTES = 1024L * 1024

class CodeModeStatementCompilerTest {
    /** CI run 37234377673: on a worker whose stack and JIT state let an 8,000 member chain compile, the end-to-end
     *  test met the engine's RangeError instead. With a deeper chain and fixed normal stack the compiler overflows every
     *  time, so this pins the compile catch: the overflow is the program's SyntaxError, recorded for the worker's
     *  retirement, and never escapes the parser. */
    @Test
    fun `a member chain that overflows the compiler ends as the program's syntax error`() {
        val source = "const v = " + "a.".repeat(COMPILER_OVERFLOW_CHAIN) + "a;"
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
            "compiler-overflow",
            COMPILER_STACK_BYTES,
        )
        reader.start()
        reader.join()
        assertTrue(checkNotNull(parsed).getOrNull() != null, "the parse fits this stack, so only the compile overflows")
        assertEquals(
            StatementInput.Failed("SyntaxError: program nesting too deep to parse"),
            checkNotNull(read).getOrThrow(),
        )
        assertTrue(parser.overflow != null, "the overflow is recorded for the worker's retirement")
    }
}
