package splice.diagnostics.doctor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.diagnostics.doctor.report.ProbeWrite
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

class DoctorProbeWriteTest {

    @Test
    fun `a perf refusal preserves the runtime port while an ordinary reset stays generic`(@TempDir tmp: Path) {
        val file = tmp.resolve("synthetic-perf.jsonl")
        val ts = System.currentTimeMillis()
        Files.writeString(
            file,
            """{"ts":$ts,"outcome":"error:conn-reset","cause":"CONNECT_REFUSED","refused_runtime_port":8123}""" + "\n",
        )
        val refused = DoctorProbeWrite().perfTailRow("synthetic", file)
        assertTrue(refused.detail.contains("couldn't reach its runtime on :8123"), refused.detail)
        Files.writeString(file, """{"ts":$ts,"outcome":"error:conn-reset"}""" + "\n")
        val reset = DoctorProbeWrite().perfTailRow("synthetic", file)
        assertTrue(!reset.detail.contains("runtime on"), reset.detail)
    }

    /** A code-mode turn's row can wait for the source round it left streaming, then land after newer rows with the
     *  time its turn ended. The newest turn is the newest by its own time, never the last line in the file. */
    @Test
    fun `a row appended late at its earlier time does not hide a newer failure`(@TempDir tmp: Path) {
        val file = tmp.resolve("synthetic-perf.jsonl")
        val ts = System.currentTimeMillis()
        val failed = """{"ts":${ts + 1},"outcome":"error:conn-reset"}"""
        val held = """{"ts":$ts,"outcome":"ok"}"""
        Files.writeString(file, failed + "\n" + held + "\n")
        val row = DoctorProbeWrite().perfTailRow("synthetic", file)
        assertEquals(CheckStatus.WARN, row.status, row.detail)
    }

    @Test
    fun `empty answers the model closed are clean turns, never a failing head`(@TempDir tmp: Path) {
        val file = tmp.resolve("synthetic-perf.jsonl")
        val ts = System.currentTimeMillis()
        val rows = (0 until 3).joinToString("") { """{"ts":${ts + it},"outcome":"empty_message"}""" + "\n" }
        Files.writeString(file, rows)
        val row = DoctorProbeWrite().perfTailRow("synthetic", file)
        assertEquals(CheckStatus.OK, row.status, row.detail)
        assertEquals("last 3 turn(s) clean", row.detail)
    }

    @Test
    fun `the probe creates and removes its own file, leaving nothing`(@TempDir tmp: Path) {
        val dir = Files.createDirectories(tmp.resolve("state"))

        val check = DoctorProbeWrite().writableProbe("state dir", dir)

        // What this pins is residue, and only residue: the probe deletes what it created, which the
        // two arms above cannot see because neither of them looks at the directory afterwards. That
        // the probe writes BYTES is a separate claim, pinned by the arm below.
        assertEquals(CheckStatus.INFO, check.status, check.detail)
        val left = Files.list(dir).use { stream -> stream.toList() }
        assertTrue(left.isEmpty(), "the probe must delete what it created, found: $left")
    }

    // DR-171 redo, on codex-splice's review. I first shipped this as a disclosed limit: deleting the
    // write survived every arm, and I recorded the surviving mutant instead of closing it. That was
    // the wrong call and the review was right — a disclosure is not coverage when the disclosed
    // property is exactly the one the port had to preserve. Creating a file and writing bytes into
    // it are different claims, and THIS probe makes the second: its non-access remedy is df, advice
    // about SPACE. Metadata can succeed where data cannot (ENOSPC, a quota, a failing device), so a
    // probe reduced to an exclusive create would report INFO over a directory that cannot take a
    // byte — turning a real failure into a clean bill of health, which is the DR-171 defect's own
    // shape wearing different clothes.
    @Test
    fun `a write failing after the exclusive create is a FAIL with the df remedy`(@TempDir tmp: Path) {
        val dir = Files.createDirectories(tmp.resolve("state"))
        val outOfSpace = ProbeWrite { _, _ -> throw java.io.IOException("No space left on device") }

        val check = DoctorProbeWrite(write = outOfSpace).writableProbe("state dir", dir)

        assertEquals(CheckStatus.FAIL, check.status, "byte writability is what this probe claims")
        assertTrue(check.fix.orEmpty().contains("df -h"), "a space failure wants df, not chmod: ${check.fix}")
    }

    // Split from the arm above rather than folded into it: "the write is load-bearing" and "the
    // failure path cleans up" fail independently, and one arm holding both reds identically for
    // either, which is the same conflation DR-170 had to undo.
    @Test
    fun `the created temp is removed on the write-failure path too`(@TempDir tmp: Path) {
        val dir = Files.createDirectories(tmp.resolve("state"))
        val outOfSpace = ProbeWrite { _, _ -> throw java.io.IOException("No space left on device") }

        DoctorProbeWrite(write = outOfSpace).writableProbe("state dir", dir)

        // The exclusive create already happened by the time the write failed, so a probe that
        // returns FAIL without cleaning up leaves a temp behind on every full-disk doctor run.
        val left = Files.list(dir).use { stream -> stream.toList() }
        assertTrue(left.isEmpty(), "the created temp must be removed on the failure path, found: $left")
    }

    // A missing dir is probed through its nearest existing ancestor, so an ancestor the daemon could
    // not create it under is the answer: not writable, the chmod on THAT ancestor, and nothing made.
    @Test
    fun `a missing dir under an unwritable ancestor is not writable, and is not created`(@TempDir tmp: Path) {
        assumeFalse(System.getProperty("user.name") == "root", "chmod is advisory for uid 0")
        val locked = Files.createDirectories(tmp.resolve("locked"))
        val dir = locked.resolve("splice-root").resolve("state")
        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("r-x------"))
        try {
            val check = DoctorProbeWrite().writableProbe("state dir", dir)

            assertEquals(CheckStatus.FAIL, check.status, check.detail)
            assertTrue("not writable" in check.detail, check.detail)
            assertEquals("chmod u+rwx $locked", check.fix, "the fix must name the ancestor that refused")
            assertFalse(Files.exists(locked.resolve("splice-root")), "the probe created part of the path")
        } finally {
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwx------"))
        }
    }
}
