// The archived perf generation's name is a contract between two modules (PerfStats writes it,
// PerfRowsFileSource reads its stamp back), so the round trip and the skip bound are pinned here once.
package splice.core.perf

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PerfArchiveNameTest {

    private val names = PerfArchiveName("claudex-perf.jsonl")

    @Test
    fun `a name carries its rotation second back, floored, and nothing else is an archive of this file`() {
        val at = 1_790_179_881_878L
        val name = names.of(at)
        assertEquals("claudex-perf.jsonl-20260923T161121Z", name)
        assertEquals(at / 1_000 * 1_000, names.rotatedAt(name))
        assertNull(names.rotatedAt("claudex-perf.jsonl"), "the live file")
        assertNull(names.rotatedAt("claudex-perf.jsonl.1"), "the live rotated generation")
        assertNull(names.rotatedAt("grok-perf.jsonl-20260923T161121Z"), "another head's archive")
        assertNull(names.rotatedAt("claudex-perf.jsonl-notastamp"), "a stray file")
    }

    @Test
    fun `a generation ends before a cutoff only when the whole stamped second is before it`() {
        val rotated = 10_000L
        // A row written 999 ms into the rotation's second can sit in the archive.
        assertFalse(names.endsBefore(rotated, 10_999))
        assertTrue(names.endsBefore(rotated, 11_000))
    }
}
