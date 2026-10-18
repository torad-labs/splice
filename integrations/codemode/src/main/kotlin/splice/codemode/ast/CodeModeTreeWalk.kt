package splice.codemode.ast

import com.oracle.js.parser.ir.LexicalContext
import com.oracle.js.parser.ir.LexicalContextNode
import com.oracle.js.parser.ir.Node
import com.oracle.js.parser.ir.visitor.NodeVisitor

/** Walks parsed statements without putting JavaScript nesting on the JVM stack. */
internal object CodeModeTreeWalk {
    fun walk(root: Node, visitor: Visitor) {
        val pending = ArrayDeque<Step>()
        val children = Children()
        pending.addLast(Step(root))
        while (pending.isNotEmpty()) {
            val (node, leaving) = pending.removeLast()
            if (leaving) {
                leave(node, visitor)
            } else {
                enter(node, visitor, pending, children)
            }
        }
    }

    private fun enter(node: Node, visitor: Visitor, pending: ArrayDeque<Step>, children: Children) {
        if (node is LexicalContextNode) visitor.context.push(node)
        if (visitor.enter(node)) {
            pending.addLast(Step(node, leaving = true))
            children.collect(node)
            for (index in children.nodes.indices.reversed()) pending.addLast(Step(children.nodes[index]))
        } else if (node is LexicalContextNode) {
            visitor.context.pop(node)
        }
    }

    private fun leave(node: Node, visitor: Visitor) {
        visitor.leave(node)
        if (node is LexicalContextNode) visitor.context.pop(node)
    }

    interface Visitor {
        val context: LexicalContext
        fun enter(node: Node): Boolean
        fun leave(node: Node) = Unit
    }

    private data class Step(val node: Node, val leaving: Boolean = false)

    private class Children : NodeVisitor<LexicalContext>(LexicalContext()) {
        val nodes = mutableListOf<Node>()
        private var root: Node? = null

        fun collect(node: Node) {
            nodes.clear()
            root = node
            node.accept(this)
            root = null
        }

        override fun enterDefault(node: Node): Boolean {
            if (node === root) return true
            nodes += node
            return false
        }
    }
}
