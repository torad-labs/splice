// NEW: only direct conversions of proven primitive expressions can use a producer-sealed intrinsic early.
package splice.codemode

import com.oracle.js.parser.ir.AccessNode
import com.oracle.js.parser.ir.BinaryNode
import com.oracle.js.parser.ir.CallNode
import com.oracle.js.parser.ir.Expression
import com.oracle.js.parser.ir.FunctionNode
import com.oracle.js.parser.ir.IdentNode
import com.oracle.js.parser.ir.JoinPredecessorExpression
import com.oracle.js.parser.ir.LexicalContext
import com.oracle.js.parser.ir.LiteralNode
import com.oracle.js.parser.ir.Scope
import com.oracle.js.parser.ir.VarNode
import com.oracle.js.parser.ir.visitor.NodeVisitor

internal class CodeModeIntrinsicReads(body: FunctionNode) {
    private val primitives = mutableSetOf<Pair<Scope, String>>()
    private val writes = mutableListOf<Pair<Pair<Scope, String>, Expression>>()
    private val admitted = mutableSetOf<String>()
    private val unsafe = mutableSetOf<String>()
    private val capturedArguments = mutableMapOf<String, MutableSet<String>>()

    val names: Map<String, List<String>> get() = (admitted - unsafe).associateWith {
        capturedArguments[it].orEmpty().toList()
    }
    var requiresCompleteSource: Boolean = false
        private set

    init {
        body.accept(object : NodeVisitor<LexicalContext>(LexicalContext()) {
            override fun enterFunctionNode(node: FunctionNode): Boolean {
                if (node !== body && node.isAsync) requiresCompleteSource = true
                return true
            }

            override fun enterCallNode(node: CallNode): Boolean {
                val property = (node.function as? AccessNode)?.property
                if (property in setOf("then", "catch", "finally")) requiresCompleteSource = true
                if (!knownCall(node.function)) requiresCompleteSource = true
                return true
            }

            override fun enterVarNode(node: VarNode): Boolean {
                val target = key(node.name.name, lc.currentScope)
                if (target != null) {
                    if (literal(node.init)) primitives += target
                    node.init?.let { writes += target to it }
                }
                return true
            }

            override fun enterBinaryNode(node: BinaryNode): Boolean {
                if (node.isAssignment) {
                    val target = node.assignmentDest as? IdentNode
                    if (target != null) {
                        key(target.name, lc.currentScope)?.let { writes += it to node.assignmentSource }
                    }
                }
                return true
            }
        })
        do {
            val before = primitives.size
            writes.forEach { (target, expression) ->
                if (!primitive(expression, target.first)) primitives -= target
            }
        } while (primitives.size != before)
    }

    fun call(node: CallNode, scope: Scope?): Boolean {
        val target = node.function as? IdentNode ?: return false
        val argument = node.args.singleOrNull() ?: return false
        return if (argument is IdentNode && key(argument.name, scope) == null) {
            capturedArguments.getOrPut(target.name) { linkedSetOf() } += argument.name
            true
        } else {
            primitive(argument, scope)
        }
    }

    fun reference(name: String, approved: Boolean) {
        if (name !in CodeModeScopeSeal.conversions) return
        if (approved) admitted += name else unsafe += name
    }

    private fun knownCall(expression: Expression): Boolean = when (expression) {
        is IdentNode -> expression.name in CodeModeScopeSeal.conversions || expression.name in setOf("text", "exit")
        is AccessNode -> (expression.base as? IdentNode)?.name in setOf("tools", "console")
        else -> false
    }

    private fun primitive(expression: Expression, scope: Scope?): Boolean = when (expression) {
        is LiteralNode<*> -> literal(expression)
        is IdentNode -> key(expression.name, scope) in primitives
        is JoinPredecessorExpression -> primitive(expression.expression, scope)
        else -> false
    }

    private fun literal(expression: Expression?): Boolean {
        if (expression !is LiteralNode<*>) return false
        return when (expression.value) {
            null -> true
            is Boolean -> true
            is Number -> true
            else -> expression.isString
        }
    }

    private fun key(name: String, scope: Scope?): Pair<Scope, String>? =
        generateSequence(scope) { it.parent }.firstOrNull { it.hasSymbol(name) }?.let { it to name }
}
