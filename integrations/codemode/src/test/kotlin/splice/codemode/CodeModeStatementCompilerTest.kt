package splice.codemode

import com.oracle.js.parser.ErrorManager
import com.oracle.js.parser.Parser
import com.oracle.js.parser.ScriptEnvironment
import com.oracle.js.parser.Source
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CodeModeStatementCompilerTest {
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
