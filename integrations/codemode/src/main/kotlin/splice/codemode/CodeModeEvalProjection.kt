// NEW: direct eval stays a native call; only its references to the shared script scope are projected.
package splice.codemode

import com.oracle.js.parser.ErrorManager
import com.oracle.js.parser.Parser
import com.oracle.js.parser.ScriptEnvironment
import com.oracle.js.parser.Source
import kotlinx.serialization.json.jsonArray
import splice.core.util.JsonScalars.str

internal object CodeModeEvalProjection {
    fun compile(source: String, scopeName: String, rawLocals: String): String {
        val errors = object : ErrorManager() {
            override fun message(message: String) = Unit
        }
        val body = Parser(
            ScriptEnvironment.builder().strict(true).build(),
            Source.sourceFor("splice-eval", source),
            errors,
        ).parse()
        // Invalid source is left to native direct eval so its SyntaxError and diagnostic remain native.
        if (errors.hasErrors() || body == null) return source
        val locals = CodeModeJson.codec.parseToJsonElement(rawLocals).jsonArray.map { requireNotNull(str(it)) }
        val declarations = body.body.symbols.filterNot { it.isInternal || it.isArguments }.map { it.name }
        return CodeModeStatementCompiler(source, body, scopeName, (locals + declarations).toSet()).compile().source
    }
}
