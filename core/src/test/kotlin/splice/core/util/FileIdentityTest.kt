// NEW: a file's identity survives in-place writes and changes when a new file takes its name.
package splice.core.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

class FileIdentityTest {
    @Test
    fun `identity survives in-place writes and changes when a new file takes the name`(@TempDir dir: Path) {
        val file = dir.resolve("transcript.jsonl")
        Files.writeString(file, "one\n")
        val first = FileStat(file, "size").identity
        // The product runs on unix hosts, so the unix view must answer here.
        assertNotNull(first)
        Files.writeString(file, "one\ntwo\n")
        assertEquals(first, FileStat(file, "size").identity)
        val replacement = dir.resolve("replacement.jsonl")
        Files.writeString(replacement, "other\n")
        Files.move(replacement, file, StandardCopyOption.REPLACE_EXISTING)
        assertNotEquals(first, FileStat(file, "size").identity)
    }
}
