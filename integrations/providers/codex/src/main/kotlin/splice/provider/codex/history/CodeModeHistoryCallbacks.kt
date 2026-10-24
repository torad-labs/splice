package splice.provider.codex.history

/** Callback proofs share unchanged balanced map nodes across every retained branch. */
internal class CodeModeHistoryCallbacks internal constructor(internal val root: HistoryCallbackNode?) :
    AbstractMap<String, CodeModeCallbackReceipt>() {
    override val entries: Set<Map.Entry<String, CodeModeCallbackReceipt>>
        get() = object : AbstractSet<Map.Entry<String, CodeModeCallbackReceipt>>() {
            override val size: Int get() = root?.size ?: 0
            override fun iterator(): Iterator<Map.Entry<String, CodeModeCallbackReceipt>> = HistoryCallbackIterator(
                root,
            )
        }

    override fun get(key: String): CodeModeCallbackReceipt? {
        var node = root
        while (node != null) {
            val order = key.compareTo(node.key)
            node = when {
                order == 0 -> return node.value
                order < 0 -> node.left
                else -> node.right
            }
        }
        return null
    }
}

internal data class HistoryCallbackNode(
    val key: String,
    val value: CodeModeCallbackReceipt,
    val left: HistoryCallbackNode? = null,
    val right: HistoryCallbackNode? = null,
) {
    val height: Int = maxOf(left?.height ?: 0, right?.height ?: 0) + 1
    val size: Int = (left?.size ?: 0) + (right?.size ?: 0) + 1
}

internal object HistoryCallbackTrees {
    fun patch(
        base: Map<String, CodeModeCallbackReceipt>,
        changes: Map<String, CodeModeCallbackReceipt>,
        removed: Set<String>,
    ): Map<String, CodeModeCallbackReceipt> {
        var root = (base as? CodeModeHistoryCallbacks)?.root
        if (base !is CodeModeHistoryCallbacks) base.forEach { (key, value) -> root = put(root, key, value) }
        removed.forEach { root = remove(root, it) }
        changes.forEach { (key, value) -> root = put(root, key, value) }
        return CodeModeHistoryCallbacks(root)
    }

    private fun put(node: HistoryCallbackNode?, key: String, value: CodeModeCallbackReceipt): HistoryCallbackNode {
        if (node == null) return HistoryCallbackNode(key, value)
        val order = key.compareTo(node.key)
        val updated = when {
            order < 0 -> HistoryCallbackNode(node.key, node.value, put(node.left, key, value), node.right)
            order > 0 -> HistoryCallbackNode(node.key, node.value, node.left, put(node.right, key, value))
            else -> HistoryCallbackNode(key, value, node.left, node.right)
        }
        return balance(updated)
    }

    private fun remove(node: HistoryCallbackNode?, key: String): HistoryCallbackNode? {
        if (node == null) return null
        val order = key.compareTo(node.key)
        return when {
            order < 0 -> balance(HistoryCallbackNode(node.key, node.value, remove(node.left, key), node.right))
            order > 0 -> balance(HistoryCallbackNode(node.key, node.value, node.left, remove(node.right, key)))
            else -> joinChildren(node)
        }
    }

    private fun joinChildren(node: HistoryCallbackNode): HistoryCallbackNode? {
        val right = node.right ?: return node.left
        var next = right
        while (next.left != null) next = checkNotNull(next.left)
        return balance(HistoryCallbackNode(next.key, next.value, node.left, remove(right, next.key)))
    }

    private fun balance(node: HistoryCallbackNode): HistoryCallbackNode {
        val difference = height(node.left) - height(node.right)
        return when {
            difference > 1 -> {
                val left = checkNotNull(node.left)
                val balanced = if (height(left.left) >= height(left.right)) left else leftRotation(left)
                rightRotation(HistoryCallbackNode(node.key, node.value, balanced, node.right))
            }
            difference < -1 -> {
                val right = checkNotNull(node.right)
                val balanced = if (height(right.right) >= height(right.left)) right else rightRotation(right)
                leftRotation(HistoryCallbackNode(node.key, node.value, node.left, balanced))
            }
            else -> node
        }
    }

    private fun height(node: HistoryCallbackNode?): Int = node?.height ?: 0

    private fun leftRotation(node: HistoryCallbackNode): HistoryCallbackNode {
        val right = checkNotNull(node.right)
        return HistoryCallbackNode(
            right.key,
            right.value,
            HistoryCallbackNode(node.key, node.value, node.left, right.left),
            right.right,
        )
    }

    private fun rightRotation(node: HistoryCallbackNode): HistoryCallbackNode {
        val left = checkNotNull(node.left)
        return HistoryCallbackNode(
            left.key,
            left.value,
            left.left,
            HistoryCallbackNode(node.key, node.value, left.right, node.right),
        )
    }
}

private class HistoryCallbackIterator(root: HistoryCallbackNode?) :
    Iterator<Map.Entry<String, CodeModeCallbackReceipt>> {
    private val pending = ArrayDeque<HistoryCallbackNode>()

    init { descend(root) }

    private fun descend(root: HistoryCallbackNode?) {
        var node = root
        while (node != null) {
            pending.addLast(node)
            node = node.left
        }
    }

    override fun hasNext(): Boolean = pending.isNotEmpty()

    override fun next(): Map.Entry<String, CodeModeCallbackReceipt> {
        if (!hasNext()) throw NoSuchElementException("History callback iterator is exhausted")
        val node = pending.removeLast()
        descend(node.right)
        return object : Map.Entry<String, CodeModeCallbackReceipt> {
            override val key = node.key
            override val value = node.value
        }
    }
}
