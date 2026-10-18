// NEW: parsed lexical references use captured binding cells, never an async with-scope environment.
package splice.codemode

import com.oracle.js.parser.Token
import com.oracle.js.parser.TokenType
import com.oracle.js.parser.ir.CallNode
import com.oracle.js.parser.ir.FunctionNode
import com.oracle.js.parser.ir.IdentNode
import com.oracle.js.parser.ir.LexicalContext
import com.oracle.js.parser.ir.Node
import com.oracle.js.parser.ir.PropertyNode
import com.oracle.js.parser.ir.Scope
import com.oracle.js.parser.ir.UnaryNode
import com.oracle.js.parser.ir.VarNode
import kotlinx.serialization.json.JsonPrimitive
import splice.codemode.ast.CodeModeTreeWalk

internal data class CompiledStatement(
    val source: String,
    val scopeName: String,
    val reads: List<String>,
    val dependencies: Map<String, List<String>>,
    val intrinsicReads: Map<String, List<String>>,
    val requiresCompleteSource: Boolean,
)
private data class StatementReplacement(val start: Int, val finish: Int, val text: String)

/** Native locals keep their declarations; only references to the persistent script scope are projected. */
internal class CodeModeStatementCompiler(
    private val source: String,
    private val body: FunctionNode,
    private val scopeName: String = generateSequence("__splice_bindings") { "${it}_" }.first { it !in source },
    private val nativeNames: Set<String> = emptySet(),
) {
    private val edits = mutableListOf<StatementReplacement>()
    private val readiness = CodeModeStatementReads(body)
    private val intrinsics = CodeModeIntrinsicReads(body)
    private val intrinsicTargets = mutableSetOf<Int>()
    private val directEvalTargets = mutableSetOf<Int>()
    private val shorthand = mutableSetOf<Int>()
    private val callTargets = mutableSetOf<Int>()
    private val bindingTargets = mutableSetOf<Int>()

    fun compile(): CompiledStatement {
        CodeModeTreeWalk.walk(body, References())
        val rewritten = StringBuilder(source)
        val order = compareByDescending<StatementReplacement> { it.start }.thenByDescending { it.finish }
        edits.distinct().sortedWith(order).forEach { edit ->
            rewritten.replace(edit.start, edit.finish, edit.text)
        }
        return CompiledStatement(
            rewritten.toString(),
            scopeName,
            readiness.reads,
            readiness.dependencies,
            intrinsics.names,
            intrinsics.requiresCompleteSource,
        )
    }

    private inner class References : CodeModeTreeWalk.Visitor {
        override val context = LexicalContext()

        override fun enter(node: Node): Boolean = when (node) {
            is VarNode -> enterVarNode(node)
            is CallNode -> enterCallNode(node)
            is PropertyNode -> enterPropertyNode(node)
            is UnaryNode -> enterUnaryNode(node)
            is IdentNode -> enterIdentNode(node)
            else -> true
        }

        private fun enterVarNode(node: VarNode): Boolean {
            bindingTargets += node.name.start
            return true
        }

        private fun enterCallNode(node: CallNode): Boolean {
            val target = node.function as? IdentNode ?: return true
            if (intrinsics.call(node, context.currentScope)) intrinsicTargets += target.start
            if (node.isEval && bindingScope(target.name, context.currentScope) == null) {
                directEvalTargets += target.start
                remember(target)
                node.args.firstOrNull()?.let { argument ->
                    val locals = generateSequence(context.currentScope) { it.parent }
                        .takeWhile { it !== body.body.scope }.flatMap { it.symbols.asSequence() }
                        .map { it.name }.toSet()
                    val names = kotlinx.serialization.json.JsonArray(locals.map(::JsonPrimitive))
                    edits += StatementReplacement(argument.start, argument.start, "$scopeName.evalSource(")
                    val suffix = ", ${JsonPrimitive(scopeName)}, '$names')"
                    edits += StatementReplacement(argument.finish, argument.finish, suffix)
                }
            } else {
                callTargets += target.start
            }
            return true
        }

        private fun enterPropertyNode(node: PropertyNode): Boolean {
            val key = node.key as? IdentNode
            val value = node.value as? IdentNode
            if (value == null) return true
            if (node.isComputed) return true
            if (key?.start == value.start) shorthand += value.start
            return true
        }

        private fun enterUnaryNode(node: UnaryNode): Boolean {
            val identifier = node.expression as? IdentNode ?: return true
            if (Token.descType(node.token) != TokenType.TYPEOF || !project(identifier)) return true
            remember(identifier)
            val name = JsonPrimitive(identifier.name)
            edits += StatementReplacement(node.start, node.finish, "$scopeName.typeOf($name)")
            return false
        }

        private fun enterIdentNode(node: IdentNode): Boolean {
            if (project(node)) {
                remember(node)
                val property = "$scopeName.values[${JsonPrimitive(node.name)}]"
                val replacement = when (node.start) {
                    in shorthand -> "${node.name}: $property"
                    in callTargets -> "(0, $property)"
                    else -> property
                }
                edits += StatementReplacement(node.start, node.finish, replacement)
            }
            return true
        }

        private fun project(node: IdentNode): Boolean = when {
            nativeIdentifier(node) -> false
            node.isDeclaredHere -> false
            node.isInitializedHere -> false
            node.isPropertyName -> false
            node.isInternal -> false
            node.isThis || node.isSuper -> false
            else -> bindingScope(node.name, context.currentScope) in listOf(null, body.body.scope)
        }

        private fun nativeIdentifier(node: IdentNode): Boolean =
            node.name in nativeNames || node.name == "arguments" ||
                node.start in directEvalTargets || node.start in bindingTargets

        private fun remember(node: IdentNode) {
            readiness.remember(node, context)
            intrinsics.reference(node.name, node.start in intrinsicTargets, node.start)
        }
    }

    private fun bindingScope(name: String, initial: Scope?): Scope? =
        generateSequence(initial) { it.parent }.firstOrNull { it.hasSymbol(name) }
}
