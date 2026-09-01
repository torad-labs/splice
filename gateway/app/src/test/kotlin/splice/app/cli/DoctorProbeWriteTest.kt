// DR-171: the writability probe as a unit, because the defect is about WHERE the probe's bytes
// land — a question the end-to-end doctor scenarios cannot ask. DoctorCommandTest already pins the
// unwritable-state-dir path end to end (FAIL, the chmod remedy, no residue), so this file stays on
// the one property that arm cannot see: a pre-existing entry at the probe's name is never written
// THROUGH. Its subject is the pre-DR-171 fixed name, which production no longer uses; a planted
// entry there is exactly the local peer's plant, and the assertions are about the VICTIM rather
// than about the probe's own verdict.
package splice.app.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

// The name production resolved before DR-171. Kept here deliberately: once the fix lands, this
// string exists ONLY in the test, and that is the point — it is the attacker's chosen path, not a
// production constant to be shared.
private const val LEGACY_PROBE_NAME = ".splice-doctor-write-probe"
private const val SENTINEL = "do-not-truncate-me"

class DoctorProbeWriteTest {

    @Test
    fun `a symlink planted at the old probe name cannot truncate its victim - DR-171`(@TempDir tmp: Path) {
        val dir = Files.createDirectories(tmp.resolve("state"))
        val victim = tmp.resolve("victim.txt")
        Files.writeString(victim, SENTINEL)
        Files.createSymbolicLink(dir.resolve(LEGACY_PROBE_NAME), victim)

        val check = DoctorProbeWrite().writableProbe("state dir", dir)

        // The victim is the assertion. Before DR-171 the probe followed the link and left this file
        // holding the five bytes "probe", while still reporting INFO — damage under a clean verdict.
        assertEquals(SENTINEL, Files.readString(victim), "the probe must never write THROUGH a planted symlink")
        assertEquals(CheckStatus.INFO, check.status, "a writable dir still probes clean: ${check.detail}")
    }

    @Test
    fun `a dangling symlink at the old probe name is not materialized - DR-171`(@TempDir tmp: Path) {
        val dir = Files.createDirectories(tmp.resolve("state"))
        val attackerChosen = tmp.resolve("attacker-chosen.txt")
        Files.createSymbolicLink(dir.resolve(LEGACY_PROBE_NAME), attackerChosen)

        val check = DoctorProbeWrite().writableProbe("state dir", dir)

        // The second face of the same defect, and the one a truncation-only test misses: following a
        // DANGLING link does not truncate anything, it CREATES the target. Doctor would have written
        // a file at a path chosen by whoever planted the link.
        assertFalse(Files.exists(attackerChosen), "following a dangling link would create the attacker's file")
        assertEquals(CheckStatus.INFO, check.status, "a writable dir still probes clean: ${check.detail}")
    }

    @Test
    fun `the probe creates and removes its own file, leaving nothing - DR-171 control`(@TempDir tmp: Path) {
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
    fun `a write failing after the exclusive create is a FAIL with the df remedy - DR-171`(@TempDir tmp: Path) {
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
    fun `the created temp is removed on the write-failure path too - DR-171`(@TempDir tmp: Path) {
        val dir = Files.createDirectories(tmp.resolve("state"))
        val outOfSpace = ProbeWrite { _, _ -> throw java.io.IOException("No space left on device") }

        DoctorProbeWrite(write = outOfSpace).writableProbe("state dir", dir)

        // The exclusive create already happened by the time the write failed, so a probe that
        // returns FAIL without cleaning up leaves a temp behind on every full-disk doctor run.
        val left = Files.list(dir).use { stream -> stream.toList() }
        assertTrue(left.isEmpty(), "the created temp must be removed on the failure path, found: $left")
    }
}
