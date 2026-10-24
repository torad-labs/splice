package splice.provider.codex.history.durable

import kotlinx.serialization.json.JsonElement

/** A persistent balanced sequence. Every branch shares its unchanged prefix without retaining request sublists. */
internal class CodeModeHistoryItems internal constructor(
    private val root: HistoryItemNode?,
) : AbstractList<JsonElement>() {
    override val size: Int get() = root?.size ?: 0

    override fun get(index: Int): JsonElement {
        require(index in indices) { "History item index is out of bounds" }
        var at = index
        var node = checkNotNull(root)
        while (node is HistoryItemBranch) {
            if (at < node.left.size) {
                node = node.left
            } else {
                at -= node.left.size
                node = node.right
            }
        }
        return when (node) {
            is HistoryItemLeaf -> node.items[at]
            is HistoryItemBranch -> error("History item traversal did not reach a leaf")
        }
    }

    override fun iterator(): Iterator<JsonElement> = HistoryItemIterator(root)

    internal fun prefix(count: Int): HistoryItemNode? = HistoryItemTrees.take(root, count)
}

internal sealed interface HistoryItemNode {
    val size: Int
    val height: Int
}

internal data class HistoryItemLeaf(val items: List<JsonElement>) : HistoryItemNode {
    override val size: Int get() = items.size
    override val height: Int get() = 1
}

internal data class HistoryItemBranch(val left: HistoryItemNode, val right: HistoryItemNode) : HistoryItemNode {
    override val size: Int = left.size + right.size
    override val height: Int = maxOf(left.height, right.height) + 1
}

internal object HistoryItemTrees {
    fun splice(base: List<JsonElement>, prefix: Int, tail: List<JsonElement>): List<JsonElement> {
        require(prefix in 0..base.size) { "History prefix is out of bounds" }
        val inherited = if (base is CodeModeHistoryItems) base.prefix(prefix) else leaf(base.take(prefix))
        val joined = join(inherited, leaf(tail.toList()))
        return CodeModeHistoryItems(joined)
    }

    fun take(node: HistoryItemNode?, count: Int): HistoryItemNode? = when {
        node == null || count == 0 -> null
        count == node.size -> node
        node is HistoryItemLeaf -> leaf(node.items.take(count))
        node is HistoryItemBranch && count <= node.left.size -> take(node.left, count)
        node is HistoryItemBranch -> join(node.left, take(node.right, count - node.left.size))
        else -> error("Invalid history item tree")
    }

    private fun branch(node: HistoryItemNode): HistoryItemBranch = when (node) {
        is HistoryItemLeaf -> error("History item balancing expected a branch")
        is HistoryItemBranch -> node
    }

    private fun leaf(items: List<JsonElement>): HistoryItemNode? = items.takeIf { it.isNotEmpty() }?.let(
        ::HistoryItemLeaf,
    )

    private fun join(left: HistoryItemNode?, right: HistoryItemNode?): HistoryItemNode? = when {
        left == null -> right
        right == null -> left
        left.height > right.height + 1 -> {
            val branch = branch(left)
            balanced(branch.left, checkNotNull(join(branch.right, right)))
        }
        right.height > left.height + 1 -> {
            val branch = branch(right)
            balanced(checkNotNull(join(left, branch.left)), branch.right)
        }
        else -> HistoryItemBranch(left, right)
    }

    private fun balanced(left: HistoryItemNode, right: HistoryItemNode): HistoryItemNode = when {
        left.height > right.height + 1 -> rotateRight(branch(left), right)
        right.height > left.height + 1 -> rotateLeft(left, branch(right))
        else -> HistoryItemBranch(left, right)
    }

    private fun rotateRight(left: HistoryItemBranch, right: HistoryItemNode): HistoryItemNode =
        if (left.left.height >= left.right.height) {
            HistoryItemBranch(left.left, HistoryItemBranch(left.right, right))
        } else {
            val middle = branch(left.right)
            HistoryItemBranch(HistoryItemBranch(left.left, middle.left), HistoryItemBranch(middle.right, right))
        }

    private fun rotateLeft(left: HistoryItemNode, right: HistoryItemBranch): HistoryItemNode =
        if (right.right.height >= right.left.height) {
            HistoryItemBranch(HistoryItemBranch(left, right.left), right.right)
        } else {
            val middle = branch(right.left)
            HistoryItemBranch(HistoryItemBranch(left, middle.left), HistoryItemBranch(middle.right, right.right))
        }
}

private class HistoryItemIterator(root: HistoryItemNode?) : Iterator<JsonElement> {
    private val pending = ArrayDeque<HistoryItemNode>()
    private var leaf: Iterator<JsonElement> = emptyList<JsonElement>().iterator()

    init { root?.let(pending::addLast) }

    override fun hasNext(): Boolean {
        while (!leaf.hasNext() && pending.isNotEmpty()) {
            when (val node = pending.removeLast()) {
                is HistoryItemLeaf -> leaf = node.items.iterator()
                is HistoryItemBranch -> {
                    pending.addLast(node.right)
                    pending.addLast(node.left)
                }
            }
        }
        return leaf.hasNext()
    }

    override fun next(): JsonElement {
        if (!hasNext()) throw NoSuchElementException("History item iterator is exhausted")
        return leaf.next()
    }
}
