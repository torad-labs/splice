// Oct 11, 2026 — the dense retained lines behave exactly as the object ones do: the same lines in the same order after
// any run of the operations the cache performs, whatever the lines carry.
package splice.app.sources

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.usage.perf.PerfRow
import splice.usage.perf.PerfTranscriptLink
import splice.usage.perf.PerfTurnFacts
import java.nio.file.Path
import kotlin.random.Random

private val KEYS = listOf("ts", "total", "first_byte", "in_tokens", "out_tokens", "cached_tokens")

class PerfDenseEntriesTest {
    private val names = PerfFieldNames(1_024L * 1_024, KEYS)
    private val generation = PerfGenerationRef(Path.of("/tmp/synthetic.jsonl"))

    private fun fields(random: Random): PerfNumericFields {
        val builder = PerfNumericBuilder(names)
        builder.put("ts", random.nextLong(1, 2_000_000_000_000L))
        KEYS.drop(1).forEach { if (random.nextInt(4) != 0) builder.put(it, random.nextLong(-5, 40_000_000_000L)) }
        return builder.fields()
    }

    private fun text(random: Random, pool: List<String>): String? = when (random.nextInt(12)) {
        0 -> null
        1 -> "inline-" + "x".repeat(200) + random.nextInt(50)
        else -> pool[random.nextInt(pool.size)]
    }

    private fun entry(random: Random, start: Long, unterminated: Boolean): PerfCachedEntry {
        val end = start + 200 + random.nextInt(2_000)
        val pool = List(6) { "value-$it" }
        val torn = random.nextInt(9) == 0
        val ts = random.nextLong(1, 2_000_000_000_000L)
        val row = if (torn) {
            null
        } else {
            PerfRow(
                ts = ts,
                outcome = text(random, pool) ?: "ok",
                fields = fields(random),
                facts = PerfTurnFacts(
                    model = text(random, pool),
                    session = text(random, pool),
                    account = text(random, pool),
                    cacheCold = listOf(null, false, true)[random.nextInt(3)],
                    compact = listOf(null, false, true)[random.nextInt(3)],
                    captured = listOf(null, false, true)[random.nextInt(3)],
                ),
                transcript = PerfTranscriptLink(sessionId = text(random, pool)),
                cause = text(random, pool),
            )
        }
        val line = PerfCachedLine(
            row = row,
            numericBytes = 0L,
            leadingTs = listOf(null, row?.ts, random.nextLong(1, 100))[random.nextInt(3)],
            model = PerfModelHint.entries[random.nextInt(3)],
            drops = PerfDropsHint(random.nextBoolean(), listOf(null, 0L, -4L, 123_456_789_012L)[random.nextInt(4)]),
            probe = row != null && random.nextInt(8) == 0,
        )
        return PerfCachedEntry(start, line, if (unterminated) "tail-$start" else null, end)
    }

    /** Everything about an entry that a reader of the list can see. */
    private fun shape(entry: PerfCachedEntry): List<Any?> {
        val row = entry.line.row
        return listOf(
            entry.start, entry.end, entry.raw, entry.line.leadingTs, entry.line.model, entry.line.drops,
            entry.line.probe,
            row?.ts, row?.outcome, row?.cause, row?.facts?.model, row?.facts?.session, row?.facts?.account,
            row?.facts?.cacheCold, row?.facts?.compact, row?.facts?.captured, row?.transcript?.sessionId,
            row?.fields?.filterKeys { it != "ts" }?.toSortedMap(),
        )
    }

    /** What the cache does with its lines, in a random order, to both lists at once. */
    private inner class Twins(seed: Int) {
        val random = Random(seed)
        val linked = PerfLinkedEntries()
        val dense = PerfDenseEntries(PerfDenseCodec(names, KEYS), generation)
        private var position = 0L

        fun append() {
            if (linked.lastOrNull()?.raw != null) {
                linked.removeLast()
                dense.removeLast()
            }
            val next = entry(random, position, unterminated = random.nextInt(6) == 0)
            position = next.end
            linked.addLast(next)
            dense.addLast(next.copy())
        }

        fun evict() {
            if (!linked.isEmpty()) assertEquals(shape(linked.removeFirst()), shape(dense.removeFirst()))
        }

        fun retract() {
            if (linked.isEmpty()) return
            val gone = linked.removeLast()
            position = gone.start
            assertEquals(shape(gone), shape(dense.removeLast()))
        }

        fun extend() {
            val last = linked.lastOrNull()?.takeIf { it.raw == null } ?: return
            linked.extendLast(last.end)
            dense.extendLast(last.end - 1)
            position = last.end
        }

        fun agree() {
            assertEquals(linked.size, dense.size)
            assertEquals(linked.lastOrNull()?.let(::shape), dense.lastOrNull()?.let(::shape))
        }
    }

    @Test
    fun `any run of the cache operations leaves the dense list holding what the object list holds`() {
        repeat(6) { seed ->
            val both = Twins(seed)
            repeat(4_000) {
                when (both.random.nextInt(10)) {
                    in 0..5 -> both.append()
                    6 -> both.evict()
                    7 -> both.retract()
                    8 -> both.extend()
                    else -> Unit
                }
                both.agree()
            }
            assertEquals(both.linked.map(::shape), both.dense.map(::shape), "seed $seed holds the same lines")
            assertTrue(both.dense.bytes > 0 || both.dense.size == 0)
        }
    }
}
