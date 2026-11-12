// NEW: only product append chains authorize suffix-only reads; all other edits need byte proof.
package splice.core.util

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class JsonlAppendProofTest {
    @Test
    fun `receipts connect consecutive reader positions through multiple product appends`(@TempDir dir: Path) {
        val file = dir.resolve("synthetic.jsonl")
        Files.writeString(file, "{}\n")
        val first = JsonlAppendProof.version(file)
        JsonlSink.appendLine(file, "{\"ts\":1}")
        val receipt = requireNotNull(JsonlAppendProof.current(file))
        val second = JsonlAppendProof.version(file)
        assertTrue(receipt.continues(first, second, null))
        JsonlSink.appendLine(file, "{\"ts\":2}")
        JsonlSink.appendLine(file, "{\"ts\":3}")
        val last = JsonlAppendProof.version(file)
        val newer = requireNotNull(JsonlAppendProof.current(file))
        assertTrue(newer.continues(first, last, null))
        assertTrue(newer.continues(second, last, receipt))
        assertFalse(newer.continues(second.copy(changed = null), last, receipt))
    }

    @Test
    fun `same growth in-place rewrites break the old append chain even after another product append`(
        @TempDir dir: Path,
    ) {
        val file = dir.resolve("synthetic.jsonl")
        Files.writeString(file, "{\"ts\":1}\n")
        JsonlSink.appendLine(file, "{\"ts\":2}")
        val before = JsonlAppendProof.version(file)
        val previous = JsonlAppendProof.current(file)
        val mtime = Files.getLastModifiedTime(file)
        Files.writeString(file, "{\"ts\":9}\n{\"ts\":8}\n")
        Files.setLastModifiedTime(file, mtime)
        JsonlSink.appendLine(file, "{\"ts\":3}")
        val after = JsonlAppendProof.version(file)
        assertFalse(requireNotNull(JsonlAppendProof.current(file)).continues(before, after, previous))
    }

    @Test
    fun `rotation truncation and injected forces never attest unchanged old prefixes`(@TempDir dir: Path) {
        val file = dir.resolve("synthetic.jsonl")
        JsonlSink.appendLine(file, "{\"ts\":1}")
        JsonlSink.appendLine(file, "{\"ts\":2}")
        assertNotNull(JsonlAppendProof.current(file))
        JsonlSink.appendLine(file, "{\"ts\":3}", maxBytes = 1L)
        assertNull(JsonlAppendProof.current(file))
        JsonlSink.appendLine(file, "{\"ts\":4}")
        val before = JsonlAppendProof.version(file)
        val receipt = JsonlAppendProof.current(file)
        Files.writeString(file, "{}\n")
        JsonlSink.appendLine(file, "{\"ts\":5}")
        assertFalse(
            requireNotNull(JsonlAppendProof.current(file)).continues(before, JsonlAppendProof.version(file), receipt),
        )
        JsonlSink.appendLine(file, "{\"ts\":6}", force = JsonlForce { _, _ -> })
        assertNull(JsonlAppendProof.current(file))
    }

    @Test
    fun `unsupported change stamps and an evicted receipt keep the optimization optional`(@TempDir dir: Path) {
        val file = dir.resolve("synthetic.jsonl")
        Files.writeString(file, "{}\n")
        val before = JsonlAppendProof.version(file)
        val after = before.copy(size = before.size + 3, changed = null)
        JsonlAppendProof.appended(file, before, after)
        assertNull(JsonlAppendProof.current(file))
        JsonlSink.appendLine(file, "{\"ts\":1}")
        JsonlAppendProof.forget(file)
        assertNull(JsonlAppendProof.current(file))
    }
}
