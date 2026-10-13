// NEW: Oct 12, 2026 — every rolled generation of a day file, not only the one JsonlSink names. JsonlSink rolls a day
// file over its `.1` when it passes DAY_MAX_BYTES and replaced the `.1` it found, so a second roll in one UTC day
// deleted the day's oldest rows unsaid (claude-splice wrote 537 MB of trace index in 3.5 h on Oct 12, a roll every
// few hours). ActivityDays now hands JsonlSink [keep], which moves the `.1` it is about to replace to the next free
// number first: `.2` is the day's first roll, `.3` its second, and `.1` always the newest. Nothing is renamed after
// it rolls except that one `.1`, which every reader already met being replaced.
//
// The trace body pack, its eviction marker and every size cap read a generation as the day it was cut from, so a
// day's rows, bytes and removal take in all of its generations (splice-lead, Oct 12).
package splice.core.storage

import splice.core.util.RotationArchive
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/** A rolled generation's number after its day file's name: `-2026-10-12.jsonl.3`. */
private val GENERATION = Regex("(?<=\\.jsonl)\\.\\d+$")

/** JsonlSink's own rolled generation, the newest one. */
internal const val NEWEST_ROLLED: String = ".1"

public object DayGenerations {

    /** Moves the `.1` JsonlSink is about to replace to the next free number, never over a file. */
    public val keep: RotationArchive = RotationArchive { rolled ->
        val base = rolled.resolveSibling(base(rolled.fileName.toString()))
        val _ = Files.move(rolled, base.resolveSibling("${base.fileName}.${older(base).size + 2}"))
    }

    /** The day file name [name] was cut from: itself unless it is a rolled generation. */
    public fun base(name: String): String = name.replace(GENERATION, "")

    /** [file]'s rolled generations oldest first, `.2` up, then `.1`; `.1` is named whether or not it is there. */
    public fun oldestFirst(file: Path): List<Path> =
        older(file) + listOf(file.resolveSibling("${file.fileName}$NEWEST_ROLLED"))

    /** The numbered generations past `.1` that are on disk, oldest first. They run unbroken from `.2`: [keep]
     *  takes the next number, and a day goes whole. */
    private fun older(file: Path): List<Path> =
        generateSequence(2) { it + 1 }
            .map { file.resolveSibling("${file.fileName}.$it") }
            .takeWhile { Files.exists(it, LinkOption.NOFOLLOW_LINKS) }
            .toList()
}
