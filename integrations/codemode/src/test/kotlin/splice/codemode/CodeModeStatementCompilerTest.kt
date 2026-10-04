package splice.codemode

import com.oracle.js.parser.ErrorManager
import com.oracle.js.parser.Parser
import com.oracle.js.parser.ScriptEnvironment
import com.oracle.js.parser.Source
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// why: GraalJS parses a member chain in a loop and the compiler walks it recursively, so on the stack below this chain
// leaves the compiler about five bytes a level, which no JVM frame fits; the overflow is always the compiler's.
private const val COMPILER_OVERFLOW_CHAIN = 50_000

// why: a quarter of the JVM's default 1 MB thread stack; the boundary parse of one statement needs a few kilobytes.
private const val SMALL_STACK_BYTES = 256L * 1024

class CodeModeStatementCompilerTest {
    /** CI run 37234377673: on a worker whose stack and JIT state let an 8,000 member chain compile, the end-to-end
     *  test met the engine's RangeError instead. On a thread with a fixed small stack the compiler overflows every
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
            "small-stack",
            SMALL_STACK_BYTES,
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

    @Test
    fun `eval parser implicit symbols are not exported as declarations`() {
        val source = "text(eval('x'));"
        val parser = CodeModeStatementParser(CodeModeStatementSyntax { it == source })
        parser.append(source, finished = true, error = null)
        val program = parser.next() as StatementInput.Program
        assertEquals(emptyList<StatementBinding>(), program.bindings)
    }

    @Test
    fun `unreachable short circuit operands have no readiness dependency`() {
        val source = "false && text(missing); true || text(another);"
        val parsed = Parser(
            ScriptEnvironment.builder().strict(true).build(),
            Source.sourceFor("short-circuit", source),
            object : ErrorManager() {},
        ).parseFunctionBody(false, true)
        val compiled = CodeModeStatementCompiler(source, checkNotNull(parsed)).compile()
        assertFalse("missing" in compiled.reads, compiled.reads.toString())
        assertFalse("another" in compiled.reads, compiled.reads.toString())
    }

    @Test
    fun `projection preserves local shadowing and shorthand property keys`() {
        val code = "let x=1; const f=(x)=>x+later; text({x,y,z: x}); text(typeof future); obj.x;"
        val parsed = Parser(
            ScriptEnvironment.builder().strict(true).build(),
            Source.sourceFor("scope", code),
            object : ErrorManager() {},
        ).parseFunctionBody(false, true)
        val compiled = CodeModeStatementCompiler(code, checkNotNull(parsed)).compile()
        assertTrue("(x)=>x+" in compiled.source, compiled.source)
        assertTrue("x: ${compiled.scopeName}.values[" in compiled.source, compiled.source)
        assertTrue("typeOf(\"future\")" in compiled.source, compiled.source)
        assertTrue("].x" in compiled.source, compiled.source)
        assertFalse("later" in compiled.reads, compiled.reads.toString())
        assertTrue("future" in compiled.reads, compiled.reads.toString())
    }
}
