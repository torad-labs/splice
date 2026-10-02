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
import com.oracle.js.parser.ir.visitor.NodeVisitor

internal class CodeModeStatementReads(private val body: FunctionNode) {
    private val owners = mutableMapOf<FunctionNode, String>()
    private val dead = mutableListOf<IntRange>()
    private val immediate = linkedSetOf<String>()
    private val closures = linkedMapOf<String, MutableSet<String>>()

    val reads: List<String> get() = immediate.toList()
    val dependencies: Map<String, List<String>> get() = closures.mapValues { it.value.toList() }

    init {
        body.accept(object : NodeVisitor<LexicalContext>(LexicalContext()) {
            override fun enterVarNode(node: VarNode): Boolean {
                if (lc.currentFunction === body && body.body.scope.hasSymbol(node.name.name)) {
                    (node.init as? FunctionNode)?.let { owners[it] = node.name.name }
                }
                return true
            }

            override fun enterBinaryNode(node: BinaryNode): Boolean {
                // Graal wraps both logical operands to join control-flow predecessors.
                val left = (node.lhs as? JoinPredecessorExpression)?.expression ?: node.lhs
                val value = (left as? LiteralNode<*>)?.value
                when (Token.descType(node.token)) {
                    TokenType.AND -> if (value == false) exclude(node.rhs)
                    TokenType.OR -> if (value == true) exclude(node.rhs)
                    else -> Unit
                }
                return true
            }

            override fun enterIfNode(node: IfNode): Boolean {
                when ((node.test as? LiteralNode<*>)?.value) {
                    false -> exclude(node.pass)
                    true -> node.fail?.let(::exclude)
                }
                return true
            }
        })
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
