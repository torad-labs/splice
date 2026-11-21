// NEW: 2026-11-21 — the ONE way a test waits before it rewrites a file whose change must be seen.
//
// THE RACE THIS CLOSES. A rewrite that keeps a file's size and puts its modification time back shows only in the
// file's change time (ctime), which is what JsonlAppendProof and the perf read cache tell such a rewrite by. A kernel
// that stamps changes from its coarse clock (Linux before 6.13: one tick, a millisecond or more) gives two changes
// inside one tick the same time, and nothing that reads stamps can tell them apart. A test that read a file and
// rewrote it at once passed on a slow machine and failed on a fast one whenever both fell inside one tick: the
// push-time gate's 30-core build machine failed PerfRowsCacheIntegrityTest and then JsonlAppendProofTest that way on
// Nov 21, 2026, both byte for byte the files main held.
//
// A file system that keeps no change time needs no wait: the product compares the bytes there.
package splice.core.testing

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime

/** The file system's change clock, as a test waits on it. */
object ChangeClock {
    /** Returns once the file system stamps a change later than [file]'s own change time, so the next write to [file]
     *  is one its readers can tell from what they last saw. */
    fun tickAfter(file: Path) {
        if ("unix" !in file.fileSystem.supportedFileAttributeViews()) return
        val stamped = Files.getAttribute(file, CHANGE_TIME) as FileTime
        val probe = file.resolveSibling("${file.fileName}.change-tick-probe")
        do {
            Files.writeString(probe, "")
        } while ((Files.getAttribute(probe, CHANGE_TIME) as FileTime) <= stamped)
        Files.delete(probe)
    }
}

// The change time of a file, where the file system keeps one (JsonlAppendProof reads the same attribute).
private const val CHANGE_TIME = "unix:ctime"
