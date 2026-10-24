// NEW: V4-343 — ByteWords against a byte-at-a-time oracle: every byte value at every position of a word, on
// backgrounds that make the subtraction borrow (zeros, high bytes, the pattern's own neighbours), then a seeded
// sweep of words built from the bytes a scan meets; and the view reads a word little-endian.
package splice.core.storage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.random.Random

// why: the bytes in a word
private const val WORD_BYTES = 8

// why: the values a byte takes
private const val BYTE_VALUES = 256

// why: the first byte that is not a control character, the limit the scans test strings against
private const val FIRST_PRINTABLE = 0x20

// why: enough random words that every pairing of edge bytes lands in many words, under a second
private const val SWEEP_WORDS = 200_000

// why: the sweep replays: a red names its word, and the same seed rebuilds it
private const val SEED = 4343

class ByteWordsTest {
    private val targets = listOf('"', '\\', '\n', '\r').map { it.code.toByte() }
    private val printable = ByteWords.repeated(FIRST_PRINTABLE.toByte())

    private val neighbours = targets.flatMap { listOf(it - 1, it.toInt(), it + 1) }
    private val limits =
        listOf(0, 1, FIRST_PRINTABLE - 1, FIRST_PRINTABLE, FIRST_PRINTABLE + 1, 0x7F, 0x80, 0x81, 0xFE, 0xFF)

    /** Bytes around the tests' edges: each target and its neighbours, the control limit, zero, 0x7F-0x80, 0xFF. */
    private val edges = (neighbours + limits).map { it.toByte() }

    private fun byteAt(word: Long, position: Int): Byte = (word ushr (Byte.SIZE_BITS * position)).toByte()

    private fun withByte(word: Long, position: Int, b: Byte): Long {
        val shift = Byte.SIZE_BITS * position
        return (word and (0xFFL shl shift).inv()) or (b.toUByte().toLong() shl shift)
    }

    /** The offset of the first byte [mask] found, or -1 when it found none: the answer a scan acts on. */
    private fun firstFound(mask: Long): Int = if (mask == 0L) -1 else ByteWords.first(mask)

    /** Each test finds what the same question asked a byte at a time finds first, and finds nothing when it does. */
    private fun assertOracle(word: Long) {
        val bytes = (0 until WORD_BYTES).map { byteAt(word, it) }
        val shown = "word 0x%016x".format(word)
        val masks = targets.map { ByteWords.equal(word, ByteWords.repeated(it)) }
        targets.zip(masks).forEach { (t, mask) -> assertEquals(bytes.indexOf(t), firstFound(mask), "$shown, $t") }
        val control = bytes.indexOfFirst { it.toUByte().toInt() < FIRST_PRINTABLE }
        val below = ByteWords.below(word, printable)
        assertEquals(control, firstFound(below), "$shown, below 0x20")
        val stops = bytes.indexOfFirst { it in targets || it.toUByte().toInt() < FIRST_PRINTABLE }
        assertEquals(stops, firstFound(masks.fold(below) { all, mask -> all or mask }), "$shown, any of them")
    }

    @Test
    fun `every byte value at every position, on backgrounds that make the subtraction borrow`() {
        val backgrounds = (edges + 'a'.code.toByte()).map { ByteWords.repeated(it) } +
            listOf(0x00FF00FF00FF00FFL, 0x7F807F807F807F80L, 0x0100010001000100L)
        backgrounds.forEach { background ->
            (0 until WORD_BYTES).forEach { position ->
                (0 until BYTE_VALUES).forEach { value -> assertOracle(withByte(background, position, value.toByte())) }
            }
        }
    }

    @Test
    fun `a seeded sweep of words built from the bytes a scan meets`() {
        val random = Random(SEED)
        repeat(SWEEP_WORDS) {
            var word = 0L
            (0 until WORD_BYTES).forEach { position ->
                val b = if (random.nextBoolean()) edges.random(random) else random.nextInt(BYTE_VALUES).toByte()
                word = withByte(word, position, b)
            }
            assertOracle(word)
        }
    }

    @Test
    fun `the view reads a word little-endian, its first byte lowest`() {
        val view = ByteWords.view(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9))

        assertEquals(0x0807060504030201L, view.getLong(0))
        assertEquals(0x0908070605040302L, view.getLong(1))
    }
}
