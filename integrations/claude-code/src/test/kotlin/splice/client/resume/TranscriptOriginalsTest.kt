// NEW: cross-head rewrites preserve immutable byte-exact originals outside the client's projects tree.
package splice.client.resume

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.client.resume.originals.ForcedTranscriptOriginalCopy
import splice.client.resume.originals.TranscriptOriginalCopy
import splice.client.resume.originals.TranscriptOriginals
import splice.core.config.StatePaths
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

internal class TranscriptOriginalsTest {
    @TempDir lateinit var tmp: Path

    @Test
    fun `main and nested originals survive two cross-head rewrites byte-exact`() {
        val project = tmp.resolve("synthetic-project")
        val main = write(project.resolve("synthetic.jsonl"), ROW + "\r\n")
        val nested = write(project.resolve("synthetic/subagents/nested/agent.jsonl"), "  " + ROW + "\n")
        val before = listOf(main, nested).associateWith(Files::readAllBytes)
        val paths = StatePaths(baseOverride = tmp.resolve("state"))
        val rewrite = TranscriptModelRewrite(originals = TranscriptOriginals(paths))
        rewrite.rewrite(main, "synthetic-first", listOf("synthetic-first"))
        rewrite.rewrite(main, "synthetic-second", listOf("synthetic-second"))

        val root = paths.transcriptOriginalsDir.resolve("synthetic-project")
        for ((live, bytes) in before) {
            assertArrayEquals(bytes, Files.readAllBytes(root.resolve(project.relativize(live))))
        }
        assertTrue(Files.readString(main).contains("synthetic-second"))
    }

    @Test
    fun `an unchanged resume does not snapshot history before the first real rewrite`() {
        val paths = StatePaths(baseOverride = tmp.resolve("state"))
        val main = write(tmp.resolve("project/session.jsonl"), ROW.replace("synthetic-old", "synthetic-new"))
        val rewrite = TranscriptModelRewrite(originals = TranscriptOriginals(paths))
        assertEquals(0, rewrite.rewrite(main, "synthetic-new", listOf("synthetic-new")))
        assertFalse(Files.exists(paths.transcriptOriginalsDir))
        Files.writeString(main, Files.readString(main) + "\n" + ROW)
        val before = Files.readAllBytes(main)
        rewrite.rewrite(main, "synthetic-new", listOf("synthetic-new"))
        assertArrayEquals(before, Files.readAllBytes(paths.transcriptOriginalsDir.resolve("project/session.jsonl")))
    }

    @Test
    fun `a partial copy failure leaves every live file unchanged and is not trusted on retry`() {
        val paths = StatePaths(baseOverride = tmp.resolve("state"))
        val main = write(tmp.resolve("project/session.jsonl"), ROW)
        val child = write(tmp.resolve("project/session/subagents/agent.jsonl"), ROW + "\r\n")
        val before = listOf(main, child).associateWith(Files::readAllBytes)
        var copies = 0
        val failing = TranscriptOriginalCopy { source, staged ->
            if (++copies == 2) {
                Files.writeString(staged, "partial")
                throw IOException("synthetic copy failure")
            }
            ForcedTranscriptOriginalCopy.copy(source, staged)
        }
        val rewrite = TranscriptModelRewrite(originals = TranscriptOriginals(paths, failing))
        assertThrows(IOException::class.java) { rewrite.rewrite(main, "synthetic-new", listOf("synthetic-new")) }
        for ((live, bytes) in before) assertArrayEquals(bytes, Files.readAllBytes(live))
        Files.writeString(main, "\n" + ROW, java.nio.file.StandardOpenOption.APPEND)
        val retryBytes = listOf(main, child).associateWith(Files::readAllBytes)
        val clean = TranscriptModelRewrite(originals = TranscriptOriginals(paths))
        clean.rewrite(main, "synthetic-new", listOf("synthetic-new"))
        val into = paths.transcriptOriginalsDir.resolve("project")
        for ((live, bytes) in retryBytes) {
            assertArrayEquals(bytes, Files.readAllBytes(into.resolve(main.parent.relativize(live))))
        }
        assertFalse(Files.walk(into).use { stream -> stream.anyMatch { it.fileName.toString().endsWith(".tmp") } })
    }

    @Test
    fun `new nested files are preserved later without replacing the earlier main original`() {
        val paths = StatePaths(baseOverride = tmp.resolve("state"))
        val main = write(tmp.resolve("project/session.jsonl"), ROW)
        val before = Files.readAllBytes(main)
        val rewrite = TranscriptModelRewrite(originals = TranscriptOriginals(paths))
        rewrite.rewrite(main, "synthetic-first", listOf("synthetic-first"))
        val child = write(tmp.resolve("project/session/subagents/later.jsonl"), " " + ROW + "\n")
        val childBytes = Files.readAllBytes(child)
        rewrite.rewrite(main, "synthetic-second", listOf("synthetic-second"))
        val into = paths.transcriptOriginalsDir.resolve("project")
        assertArrayEquals(before, Files.readAllBytes(into.resolve("session.jsonl")))
        assertArrayEquals(childBytes, Files.readAllBytes(into.resolve("session/subagents/later.jsonl")))
    }

    @Test
    fun `every original and directory is owner-only and the live transcript is not hardlinked`() {
        val paths = StatePaths(baseOverride = tmp.resolve("state"))
        val main = write(tmp.resolve("project/session.jsonl"), ROW)
        write(tmp.resolve("project/session/subagents/nested/agent.jsonl"), ROW)
        TranscriptModelRewrite(originals = TranscriptOriginals(paths))
            .rewrite(main, "synthetic-new", listOf("synthetic-new"))
        val originals = Files.walk(paths.transcriptOriginalsDir).use { it.toList() }
        for (path in originals) {
            val expected = if (Files.isDirectory(path)) "rwx------" else "rw-------"
            assertEquals(expected, PosixFilePermissions.toString(Files.getPosixFilePermissions(path)), path.toString())
        }
        val saved = paths.transcriptOriginalsDir.resolve("project/session.jsonl")
        assertFalse(Files.isSameFile(main, saved))
        assertEquals(1, Files.getAttribute(saved, "unix:nlink"))
    }

    @Test
    fun `the startup sweep keeps a live original and removes a positively orphaned session and children`() {
        val paths = StatePaths(baseOverride = tmp.resolve("state"))
        val store = TranscriptOriginals(paths)
        val live = write(tmp.resolve("project/live.jsonl"), ROW)
        val gone = write(tmp.resolve("project/gone.jsonl"), ROW)
        val child = write(tmp.resolve("project/gone/subagents/agent.jsonl"), ROW)
        val rewrite = TranscriptModelRewrite(originals = store)
        rewrite.rewrite(live, "synthetic-new", listOf("synthetic-new"))
        rewrite.rewrite(gone, "synthetic-new", listOf("synthetic-new"))
        Files.delete(gone)
        val logs = StringBuilder()
        store.sweep { logs.append(it) }
        val into = paths.transcriptOriginalsDir.resolve("project")
        assertTrue(Files.exists(into.resolve("live.jsonl")))
        assertFalse(Files.exists(into.resolve("gone.jsonl")))
        assertFalse(Files.exists(into.resolve("gone")))
        assertFalse(Files.exists(into.resolve("gone.sources.json")))
        assertTrue(Files.exists(child), "only original copies are removed")
        assertEquals("", logs.toString())
    }

    @Test
    fun `one surviving head copy keeps the shared original until all locations are gone`() {
        val paths = StatePaths(baseOverride = tmp.resolve("state"))
        val store = TranscriptOriginals(paths)
        val first = write(tmp.resolve("first/project/session.jsonl"), ROW)
        val second = write(tmp.resolve("second/project/session.jsonl"), ROW)
        val rewrite = TranscriptModelRewrite(originals = store)
        rewrite.rewrite(first, "synthetic-new", listOf("synthetic-new"))
        rewrite.rewrite(second, "synthetic-new", listOf("synthetic-new"))
        Files.delete(first)
        store.sweep {}
        val saved = paths.transcriptOriginalsDir.resolve("project/session.jsonl")
        assertTrue(Files.exists(saved))
        Files.delete(second)
        store.sweep {}
        assertFalse(Files.exists(saved))
    }

    @Test
    fun `an inaccessible source is not absence and keeps its original`() {
        val paths = StatePaths(baseOverride = tmp.resolve("state"))
        val store = TranscriptOriginals(paths)
        val main = write(tmp.resolve("project/session.jsonl"), ROW)
        TranscriptModelRewrite(originals = store).rewrite(main, "synthetic-new", listOf("synthetic-new"))
        val logs = StringBuilder()
        Files.setPosixFilePermissions(main.parent, PosixFilePermissions.fromString("---------"))
        try {
            store.sweep { logs.append(it) }
            assertTrue(Files.exists(paths.transcriptOriginalsDir.resolve("project/session.jsonl")))
            assertTrue(logs.contains("unresolved session"), logs.toString())
        } finally {
            Files.setPosixFilePermissions(main.parent, PosixFilePermissions.fromString("rwx------"))
        }
    }

    @Test
    fun `a row arriving during original copying is not erased by a prepared rewrite`() {
        val paths = StatePaths(baseOverride = tmp.resolve("state"))
        val main = write(tmp.resolve("project/session.jsonl"), ROW + "\n")
        val arrived = """{"type":"user","message":{"content":"arrived during copy"}}"""
        val copy = TranscriptOriginalCopy { source, staged ->
            ForcedTranscriptOriginalCopy.copy(source, staged)
            Files.writeString(source, arrived + "\n", java.nio.file.StandardOpenOption.APPEND)
        }
        val rewrite = TranscriptModelRewrite(originals = TranscriptOriginals(paths, copy))
        assertThrows(IOException::class.java) { rewrite.rewrite(main, "synthetic-new", listOf("synthetic-new")) }
        assertEquals(ROW + "\n" + arrived + "\n", Files.readString(main))
    }

    @Test
    fun `a nested transcript link outside the session cannot rewrite its target`() {
        val paths = StatePaths(baseOverride = tmp.resolve("state"))
        val main = write(tmp.resolve("project/session.jsonl"), ROW)
        val outside = write(tmp.resolve("other/project.jsonl"), ROW)
        val nested = Files.createDirectories(main.parent.resolve("session/subagents"))
        Files.createSymbolicLink(nested.resolve("linked.jsonl"), outside)
        val rewrite = TranscriptModelRewrite(originals = TranscriptOriginals(paths))
        assertThrows(IOException::class.java) { rewrite.rewrite(main, "synthetic-new", listOf("synthetic-new")) }
        assertEquals(ROW, Files.readString(main))
        assertEquals(ROW, Files.readString(outside))
    }

    @Test
    fun `a row arriving while the replacement is staged aborts the final swap`() {
        val paths = StatePaths(baseOverride = tmp.resolve("state"))
        val main = write(tmp.resolve("project/session.jsonl"), ROW + "\n")
        val arrived = """{"type":"user","message":{"content":"arrived during staging"}}"""
        val fs = object : TranscriptFs {
            override fun write(path: Path, rows: StagedRows) {
                Files.newOutputStream(path).use { rows(it) }
                Files.writeString(main, arrived + "\n", java.nio.file.StandardOpenOption.APPEND)
            }
            override fun move(source: Path, target: Path, vararg options: java.nio.file.CopyOption) {
                Files.move(source, target, *options)
            }
        }
        val rewrite = TranscriptModelRewrite(fs, TranscriptOriginals(paths))
        assertThrows(IOException::class.java) { rewrite.rewrite(main, "synthetic-new", listOf("synthetic-new")) }
        assertEquals(ROW + "\n" + arrived + "\n", Files.readString(main))
    }

    @Test
    fun `an unchanged head copy keeps its original after the earlier live location goes away`() {
        val paths = StatePaths(baseOverride = tmp.resolve("state"))
        val store = TranscriptOriginals(paths)
        val first = write(tmp.resolve("first/project/session.jsonl"), ROW)
        val rewrite = TranscriptModelRewrite(originals = store)
        rewrite.rewrite(first, "synthetic-new", listOf("synthetic-new"))
        val second = write(tmp.resolve("second/project/session.jsonl"), Files.readString(first))
        assertEquals(0, rewrite.rewrite(second, "synthetic-new", listOf("synthetic-new")))
        Files.delete(first)
        store.sweep {}
        val saved = paths.transcriptOriginalsDir.resolve("project/session.jsonl")
        assertArrayEquals(ROW.toByteArray(), Files.readAllBytes(saved))
    }

    private fun write(path: Path, text: String): Path {
        Files.createDirectories(path.parent)
        return Files.writeString(path, text)
    }
}

private const val ROW = """{"type":"assistant","message":{"model":"synthetic-old","content":[{"type":"text","text":"original"}]}}"""
