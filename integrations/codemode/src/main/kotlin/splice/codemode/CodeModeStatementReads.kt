// NEW: readiness follows captured closures without treating dead literal branches as executed reads.
package splice.codemode

import com.oracle.js.parser.Token
import com.oracle.js.parser.TokenType
import com.oracle.js.parser.ir.BinaryNode
import com.oracle.js.parser.ir.FunctionNode
import com.oracle.js.parser.ir.IdentNode
import com.oracle.js.parser.ir.IfNode
import com.oracle.js.parser.ir.JoinPredecessorExpression
import com.oracle.js.parser.ir.LexicalContext
import com.oracle.js.parser.ir.LiteralNode
import com.oracle.js.parser.ir.Node
import com.oracle.js.parser.ir.VarNode
import splice.codemode.ast.CodeModeTreeWalk

internal class CodeModeStatementReads(private val body: FunctionNode) {
    private val owners = mutableMapOf<FunctionNode, String>()
    private val dead = mutableListOf<IntRange>()
    private val immediate = linkedSetOf<String>()
    private val closures = linkedMapOf<String, MutableSet<String>>()

    val reads: List<String> get() = immediate.toList()
    val dependencies: Map<String, List<String>> get() = closures.mapValues { it.value.toList() }

    init {
        CodeModeTreeWalk.walk(
            body,
            object : CodeModeTreeWalk.Visitor {
                override val context = LexicalContext()

                override fun enter(node: Node): Boolean = when (node) {
                    is VarNode -> enterVarNode(node, context)
                    is BinaryNode -> enterBinaryNode(node)
                    is IfNode -> enterIfNode(node)
                    else -> true
                }
            },
        )
    }

    private fun enterVarNode(node: VarNode, context: LexicalContext): Boolean {
        if (context.currentFunction === body && body.body.scope.hasSymbol(node.name.name)) {
            (node.init as? FunctionNode)?.let { owners[it] = node.name.name }
        }
        return true
    }

    private fun enterBinaryNode(node: BinaryNode): Boolean {
        // Graal wraps both logical operands to join control-flow predecessors.
        val left = (node.lhs as? JoinPredecessorExpression)?.expression ?: node.lhs
        val value = (left as? LiteralNode<*>)?.value
        val operator = Token.descType(node.token)
        if (operator == TokenType.AND && value == false) exclude(node.rhs)
        if (operator == TokenType.OR && value == true) exclude(node.rhs)
        return true
    }

    private fun enterIfNode(node: IfNode): Boolean {
        when ((node.test as? LiteralNode<*>)?.value) {
            false -> exclude(node.pass)
            true -> node.fail?.let(::exclude)
        }
        return true
    }

    fun remember(node: IdentNode, context: LexicalContext) {
        if (dead.any { node.start in it }) return
        val owner = owners[context.currentFunction]
        if (owner == null) {
            immediate += node.name
        } else {
            closures.getOrPut(owner) { linkedSetOf() } += node.name
        }
    }

    private fun exclude(node: Node) {
        dead += node.start until node.finish
    }
}
