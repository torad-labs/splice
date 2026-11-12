// NEW: (JW-17, split from DoctorCommand.kt — the file sits at detekt's function budget) the
// state/log writability probe. Three subsystems (daemon.log, config persistence, usage/perf/
// compact appends) degrade silently on an unwritable state root; doctor printed the path
// but never touched it.
package splice.diagnostics.doctor

import kotlinx.serialization.json.jsonObject
import splice.core.config.UserHome
import splice.core.perf.LivenessProbe
import splice.core.perf.OutcomeTags
import splice.core.perf.PerfKeys
import splice.core.util.Cancellables
import splice.core.util.JsonScalars
import splice.core.util.SafeFailureText
import splice.diagnostics.doctor.report.DoctorRedaction
import splice.diagnostics.doctor.report.DoctorReportFiles
import splice.diagnostics.doctor.report.FileProbeWrite
import splice.diagnostics.doctor.report.ProbeWrite
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path

private val OUTCOME_TAG = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$")

/** The writability probe as a constructed collaborator rather than a free function (Kotlin style
 *  law, 2026-08-15: main sources carry no top-level functions). Stateless — doctor builds one and
 *  asks it; the member keeps the old function's name so every historical grep still lands. */
internal class DoctorProbeWrite(
    private val write: ProbeWrite = FileProbeWrite,
    private val files: DoctorReportFiles =
        DoctorReportFiles(DoctorRedaction(UserHome.dir())),
) {

    /** JW-17: write-and-delete a dot-prefixed probe in [dir]. OK carries [okDetail] (the path, or a
     *  richer label); a failure is a FAIL whose fix is chosen by cause — AccessDenied wants chmod,
     *  anything else (typically no space) wants df. Non-mutating: the probe is removed in a finally.
     *
     *  A [dir] PROVEN ABSENT is probed through its nearest existing ancestor and never created
     *  (2026-10-04). This used to create it "as the daemon would" and then delete its own file, so
     *  doctor on a home with no state root left an empty state dir behind, the artifact that took over
     *  the operator's root at the 7:27 PM CT restart. A dir that cannot be stat-ed is not absent: it
     *  is probed where it is, and the probe's own failure answers. */
    internal fun writableProbe(name: String, dir: Path, okDetail: String? = null): DoctorCheck {
        var into = dir
        // DR-171: this resolved the FIXED name ".splice-doctor-write-probe" and wrote to it, so a
        // local peer could pre-plant that name as a symlink — the write FOLLOWED it and truncated
        // the victim to the five bytes below, the finally then removed only the link, and doctor
        // reported INFO over the damage. That is DR-8 redo-3's defect in the sibling exec probe, so
        // its remedy PORTS rather than gets re-invented: createTempFile picks a random name and
        // creates it with CREATE_NEW (O_EXCL), which refuses ANY pre-existing path — symlink and
        // dangling symlink included — so the write can only land on the fresh regular file it just
        // made. Creation sits INSIDE the try, so a creation failure is reported as the probe's own
        // failure (fail-closed) rather than escaping; the finally deletes only a probe that was
        // actually created, which is why this is a nullable var and not a val.
        var probe: Path? = null
        return try {
            if (provenAbsent(dir)) into = nearestExisting(dir)
            probe = Files.createTempFile(into, ".splice-doctor-write-probe.", ".tmp")
            write(probe, "probe")
            val detail = okDetail ?: dir.toString()
            val absent = " (not created yet: the daemon creates it on its first start, and $into is writable)"
            DoctorCheck(name, CheckStatus.INFO, if (into == dir) detail else detail + absent)
        } catch (_: java.nio.file.AccessDeniedException) {
            // The label is read off the BRANCH, not off the caught throwable's runtime class: this
            // clause only ever stands in for AccessDeniedException, so naming it is a compile-time
            // fact and the reflective lookup that used to produce the same six syllables is gone.
            DoctorCheck(
                name,
                CheckStatus.FAIL,
                "${subject(dir, into)} is not writable (AccessDeniedException)",
                "chmod u+rwx $into",
                fixKind = FixKind.COMMAND,
            )
        } catch (e: java.io.IOException) {
            val why = "${subject(dir, into)} is not writable (${SafeFailureText.render(e)})"
            DoctorCheck(name, CheckStatus.FAIL, why, "check free space: df -h $into")
        } finally {
            probe?.let { p -> Cancellables.runCatchingCancellable { Files.deleteIfExists(p) } }
        }
    }

    /** Only [NoSuchFileException] is absence; any other failure to stat propagates to the probe's own
     *  FAIL arms, because a path that cannot be read may exist. */
    private fun provenAbsent(path: Path): Boolean = try {
        val _ = Files.readAttributes(path, "basic:isDirectory")
        false
    } catch (_: NoSuchFileException) {
        true
    }

    /** The closest ancestor of a proven-absent [dir] that exists: where the daemon's first start would
     *  create it, and so the directory whose writability decides whether it can. */
    private fun nearestExisting(dir: Path): Path {
        var ancestor = dir.toAbsolutePath().parent ?: return dir
        while (provenAbsent(ancestor)) ancestor = ancestor.parent ?: return dir
        return ancestor
    }

    /** What a FAIL names: the dir itself, or the dir and the ancestor that refused it. */
    private fun subject(dir: Path, into: Path): String = if (into == dir) "$dir" else "$dir cannot be created: $into"

    /** Last-N turn outcomes from the per-head perf JSONL — "last failure: 4m ago (upstream_failed)"
     *  is the sentence doctor exists to say. Read as a bounded tail of BOTH generations (a failure
     *  that rotated into .1 minutes ago still counts); a generation that cannot be read is said,
     *  never presented as no turns. Missing/empty files = INFO (a fresh head has no turns). The newest turns are the
     *  newest by time: a turn's row can wait for a source round it left streaming and land after newer rows. */
    internal fun perfTailRow(headKey: String, perfFile: Path): DoctorCheck {
        val read = files.tails(perfFile, PROBE_TAIL_BYTES)
        val rows = read.lines.mapNotNull(::perfRow).sortedBy { (_, ts) -> ts }.takeLast(PERF_TAIL_TURNS)
        val failures = rows.filter { (outcome, _) -> !OutcomeTags.isClean(outcome) }
        val name = "head $headKey turns"
        val unread = read.error?.let { " (a perf file could not be read: $it)" }.orEmpty()
        return when {
            rows.isEmpty() && read.error != null ->
                DoctorCheck(name, CheckStatus.WARN, "perf file could not be read: ${read.error}", null)
            rows.isEmpty() -> DoctorCheck(name, CheckStatus.INFO, "no turns recorded yet")
            failures.isEmpty() -> DoctorCheck(name, CheckStatus.OK, "last ${rows.size} turn(s) clean$unread")
            else -> failed(name, headKey, rows, failures, unread)
        }
    }

    private fun failed(
        name: String,
        headKey: String,
        rows: List<Triple<String, Long, Long?>>,
        failures: List<Triple<String, Long, Long?>>,
        unread: String,
    ): DoctorCheck {
        val (outcome, ts, refusedPort) = failures.last()
        val ageMs = System.currentTimeMillis() - ts
        val refusal = refusedPort?.let { "; couldn't reach its runtime on :$it" }.orEmpty()
        val last = "last failure: ${DoctorAge.ago(ageMs)} (${tag(outcome)})$refusal"
        val detail = "${failures.size} of last ${rows.size} turn(s) failed; $last$unread"
        // A row with no time (perfRow reads it as 0) is never called old.
        val recent = ts <= 0L || ageMs <= RECENT_FAILURE_MS
        val status = if (recent && stillFailing(rows)) CheckStatus.WARN else CheckStatus.INFO
        return DoctorCheck(name, status, detail, "splice logs --head $headKey --tail 50", fixKind = FixKind.COMMAND)
    }

    /** Whether the head is failing NOW, judged on its newest turns: the newest one failed, or at least
     *  [FAILING_OF_NEWEST] of its newest [NEWEST_TURNS] did and the newest two are not both clean. A burst of
     *  failures followed by two good turns has recovered and reads as history (V4-444, console review
     *  2026-09-29: claude-splice failed 18 of 20 turns, then answered twice, and still sat in Needs you). */
    private fun stillFailing(rows: List<Triple<String, Long, Long?>>): Boolean {
        val newest = rows.takeLast(NEWEST_TURNS).map { (outcome, _) -> !OutcomeTags.isClean(outcome) }
        val recovered = newest.takeLast(RECOVERY_RUN).let { it.size == RECOVERY_RUN && it.none { failed -> failed } }
        return newest.last() || (newest.count { it } >= FAILING_OF_NEWEST && !recovered)
    }

    /** One perf JSONL row -> (outcome, ts, refused runtime port); null on a malformed line (tail readers stay tolerant). */
    // A torn or malformed JSONL tail line is normal (the daemon appends while doctor reads): the row is skipped.
    internal fun perfRow(line: String): Triple<String, Long, Long?>? = try {
        val obj = kotlinx.serialization.json.Json.parseToJsonElement(line).jsonObject
        val outcome = JsonScalars.str(obj, "outcome")
        if (outcome == null || LivenessProbe.legacyRow(obj)) {
            null
        } else {
            val port = JsonScalars.long(obj, PerfKeys.REFUSED_RUNTIME_PORT)?.takeIf { it in 1..MAX_RUNTIME_PORT }
            Triple(outcome, JsonScalars.long(obj, "ts") ?: 0L, port)
        }
    } catch (_: IllegalArgumentException) {
        null
    }

    /** An outcome is a tag from the daemon's vocabulary; a perf row is a plain file, so anything
     *  else shaped is shown as ?, never quoted. */
    private fun tag(outcome: String): String = outcome.takeIf { OUTCOME_TAG.matches(it) } ?: "?"
}

// why: TCP ports occupy the unsigned 16-bit range; zero names no listener.
private const val MAX_RUNTIME_PORT = 65_535L

private const val PERF_TAIL_TURNS = 20

// why: doctor says what is wrong now. A head's newest failure older than a day is history: a live
// fault fails the head's next turn, which is recent again. Console review 2026-09-29: Needs you
// listed nine heads whose last failure was 3 to 35 days old. The age bound stays beside the newest-turns
// rule below because "the newest turn failed" is true forever of a head nobody has used since.
private const val RECENT_FAILURE_MS = 24L * 3_600_000

// why: five turns is the run an operator reads to say "it is failing now"; twenty (the tail) reads as history.
private const val NEWEST_TURNS = 5

// why: three of five is a majority of the newest turns, the same bar a person would call a head unreliable.
private const val FAILING_OF_NEWEST = 3

// why: two clean turns in a row are the shortest run that cannot be one lucky retry after a burst.
private const val RECOVERY_RUN = 2

/** Enough bytes for well over 20 rows per generation (a row is under 1 KiB). */
private const val PROBE_TAIL_BYTES = 64 shl 10
