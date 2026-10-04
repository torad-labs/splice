// NEW: one byte-stream walk of JSON container and scalar grammar.
package splice.head.trace

import splice.core.memory.HeapBudget
import splice.core.memory.HeapCapacityException
import splice.core.memory.HeapOwners
import java.nio.ByteBuffer

// why: the initial stack capacity grows only when nesting needs more space.
internal const val JSON_LINE_INITIAL_ROOM = 16

private val COLON = ':'.code.toByte()
private val COMMA = ','.code.toByte()
private val OPEN_OBJECT = '{'.code.toByte()
private val CLOSE_OBJECT = '}'.code.toByte()
private val OPEN_ARRAY = '['.code.toByte()
private val CLOSE_ARRAY = ']'.code.toByte()

/** What the next byte outside a scalar may be. */
private enum class Expect { VALUE, FIRST_ITEM, FIRST_KEY, KEY, COLON, AFTER, DONE }

/** The containers the walk is inside, innermost last. */
private class Containers(private val heap: HeapBudget) {
    private val initialLease = heap.reserve(JSON_LINE_INITIAL_ROOM.toLong()) ?: throw HeapCapacityException()
    private var stack = ByteArray(JSON_LINE_INITIAL_ROOM).also { HeapOwners.keep(it, initialLease) }

    var depth = 0
        private set

    val top: Byte get() = stack[depth - 1]

    /** Opens a container with [opener]; what its first byte may be. */
    fun open(opener: Byte): Expect {
        if (depth == stack.size) {
            val next = heap.reserve(stack.size * 2L) ?: throw HeapCapacityException()
            stack = stack.copyOf(stack.size * 2).also { HeapOwners.keep(it, next) }
        }
        stack[depth] = opener
        depth += 1
        return if (opener == OPEN_OBJECT) Expect.FIRST_KEY else Expect.FIRST_ITEM
    }

    /** Closes the innermost container when [opener] opened it: what may follow, or null when it did not. */
    fun close(opener: Byte): Expect? {
        if (depth == 0 || stack[depth - 1] != opener) return null
        depth -= 1
        return if (depth == 0) Expect.DONE else Expect.AFTER
    }
}

/** One line's walk by the grammar: the scalar being read, the containers it is in, the named members met. */
internal class JsonLineScan(names: List<Pair<String, ByteArray>>, heap: HeapBudget) {
    private var expect = Expect.VALUE
    private val containers = Containers(heap)
    private val members = JsonLineMembers(names)
    private var token: Token? = null
    private var keying = false

    private val inTopKey: Boolean get() = keying && containers.depth == 1

    /** Inside a string no one keeps and no escape, whose bytes need a look only at its end, an escape or a
     *  control character. */
    private val passing: Boolean get() = !keying && !members.capturing && (token as? Token.Text)?.plain == true

    /** Walks the first [n] bytes of [words]; false once the line is declined. */
    fun feed(words: ByteBuffer, n: Int): Boolean {
        val bytes = words.array()
        var i = 0
        var going = true
        while (going && i < n) {
            if (passing) i = ByteKinds.stringUntil(words, i, n)
            going = i >= n || step(bytes[i])
            i += 1
        }
        return going
    }

    /** The named members, when the line ended right after its one object and whitespace. */
    fun finish(): Map<String, String>? = members.found.takeIf { expect == Expect.DONE }

    private fun step(b: Byte): Boolean {
        val reading = token ?: return ByteKinds.space(b) || structure(b)
        return when (reading.feed(b)) {
            Token.Fed.MORE -> members.take(b, inTopKey)
            Token.Fed.DONE -> ended(b)
            Token.Fed.BEFORE -> ended(null) && step(b)
            Token.Fed.BAD -> false
        }
    }

    /** The scalar ended, [last] its closing byte when it had one: a key waits for its colon (a top-level one
     *  may name a member), a value is done and what follows a value may come. */
    private fun ended(last: Byte?): Boolean {
        val wasKey = keying
        val top = inTopKey
        token = null
        keying = false
        if (wasKey) {
            expect = Expect.COLON
            return !top || members.keyEnds()
        }
        val kept = last == null || members.take(last, false)
        members.valueEnds()
        expect = Expect.AFTER
        return kept
    }

    private fun structure(b: Byte): Boolean = when (expect) {
        Expect.VALUE, Expect.FIRST_ITEM -> value(b)
        Expect.FIRST_KEY, Expect.KEY -> key(b)
        Expect.COLON -> colon(b)
        Expect.AFTER -> after(b)
        Expect.DONE -> false
    }

    /** A value's first byte. The line is one object; a value where a named member's belongs starts its capture. */
    private fun value(b: Byte): Boolean {
        if (expect == Expect.FIRST_ITEM && b == CLOSE_ARRAY) return close(OPEN_ARRAY)
        val container = b == OPEN_OBJECT || b == OPEN_ARRAY
        val admitted = if (containers.depth == 0) b == OPEN_OBJECT else members.valueStarts(container)
        return admitted && if (container) open(b) else scalar(b)
    }

    private fun open(opener: Byte): Boolean {
        expect = containers.open(opener)
        return true
    }

    private fun scalar(b: Byte): Boolean {
        token = Tokens.value(b)
        return token != null && members.take(b, false)
    }

    private fun key(b: Byte): Boolean {
        if (expect == Expect.FIRST_KEY && b == CLOSE_OBJECT) return close(OPEN_OBJECT)
        val opened = Tokens.key(b, escapes = containers.depth != 1) ?: return false
        keying = true
        members.keyStarts()
        token = opened
        return true
    }

    private fun colon(b: Byte): Boolean {
        if (b != COLON) return false
        expect = Expect.VALUE
        return true
    }

    private fun after(b: Byte): Boolean = when (b) {
        COMMA -> comma()
        CLOSE_OBJECT -> close(OPEN_OBJECT)
        CLOSE_ARRAY -> close(OPEN_ARRAY)
        else -> false
    }

    private fun comma(): Boolean {
        expect = if (containers.top == OPEN_OBJECT) Expect.KEY else Expect.VALUE
        return true
    }

    private fun close(opener: Byte): Boolean {
        val next = containers.close(opener) ?: return false
        expect = next
        return true
    }
}
