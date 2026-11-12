// NEW: bounded top-level JSON member captures, independent of the structural walk.
package splice.head.trace

// why: captured stamps are bounded, while keys need only the longest requested name.
private const val MAX_CAPTURE_BYTES = 4096

/** The named members at the top of the object: each top-level key as it is read, and the raw value of every
 *  one that names a member. */
internal class JsonLineMembers(private val names: List<Pair<String, ByteArray>>) {
    private val key = KeyBytes(names.maxOfOrNull { it.second.size } ?: 0)
    private var naming: String? = null
    private var capture: Capture? = null

    val found = LinkedHashMap<String, String>()

    val capturing: Boolean get() = capture != null

    fun keyStarts() = key.reset()

    /** A top-level key ended; false when it names a member already met, which kotlinx would read twice. */
    fun keyEnds(): Boolean {
        naming = key.name(names)
        return naming?.let { it !in found } ?: true
    }

    /** A value starts; when it is a named member's, its capture starts too. False when a named member holds an
     *  object or an array, which is no record's stamp. */
    fun valueStarts(container: Boolean): Boolean {
        val name = naming ?: return true
        naming = null
        capture = Capture(name)
        return !container
    }

    /** Keeps [b]: a top-level key's byte when [inTopKey], else a named member's value byte. False once that
     *  value is longer than a member is kept. */
    fun take(b: Byte, inTopKey: Boolean): Boolean {
        if (inTopKey) key.add(b)
        return inTopKey || capture?.add(b) ?: true
    }

    fun valueEnds() {
        capture?.let { found[it.name] = it.text() }
        capture = null
    }
}

/** A top-level key's bytes, as far as the longest named member reaches. */
private class KeyBytes(longest: Int) {
    private val bytes = ByteArray(longest + 1)
    private var length = 0

    fun reset() {
        length = 0
    }

    fun add(b: Byte) {
        if (length < bytes.size) bytes[length] = b
        length += 1
    }

    /** The named member this key is, if any. */
    fun name(names: List<Pair<String, ByteArray>>): String? =
        names.firstOrNull { (_, name) -> name.size == length && name.indices.all { name[it] == bytes[it] } }?.first
}

/** A named member's value as it is read: its raw bytes, up to [MAX_CAPTURE_BYTES]. */
private class Capture(val name: String) {
    private var bytes = ByteArray(JSON_LINE_INITIAL_ROOM)
    private var length = 0

    /** False once the value is longer than a named member is kept. */
    fun add(b: Byte): Boolean {
        if (length == MAX_CAPTURE_BYTES) return false
        if (length == bytes.size) bytes = bytes.copyOf(minOf(bytes.size * 2, MAX_CAPTURE_BYTES))
        bytes[length] = b
        length += 1
        return true
    }

    /** The value's text, decoded leniently as the line is: a malformed byte reads as U+FFFD. */
    fun text(): String = String(bytes, 0, length, Charsets.UTF_8)
}
