// NEW: (JW-17, split from DoctorCommand.kt — the file sits at detekt's function budget) the
// state/log writability probe. Three subsystems (daemon.log, config persistence, usage/perf/
// compact appends) degrade silently on an unwritable state root; doctor printed the path
// but never touched it.
package splice.app.cli.doctor

import kotlinx.serialization.json.jsonObject
import splice.core.util.Cancellables
import splice.core.util.JsonScalars
import splice.core.util.SafeFailureText
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

private val OUTCOME_TAG = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$")

/** The writability probe as a constructed collaborator rather than a free function (Kotlin style
 *  law, 2026-08-15: main sources carry no top-level functions). Stateless — doctor builds one and
 *  asks it; the member keeps the old function's name so every historical grep still lands. */
internal class DoctorProbeWrite(
    private val write: ProbeWrite = FileProbeWrite,
    private val files: DoctorReportFiles =
        DoctorReportFiles(DoctorRedaction(Paths.get(System.getProperty("user.home")))),
) {

    /** JW-17: write-and-delete a dot-prefixed probe in [dir] (created first, as the daemon would).
     *  OK carries [okDetail] (the path, or a richer label); a failure is a FAIL whose fix is chosen
     *  by cause — AccessDenied wants chmod, anything else (typically no space) wants df. Non-mutating
     *  in spirit: the probe is removed in a finally. */
    internal fun writableProbe(name: String, dir: Path, okDetail: String? = null): DoctorCheck {
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
            Files.createDirectories(dir)
            probe = Files.createTempFile(dir, ".splice-doctor-write-probe.", ".tmp")
            write(probe, "probe")
            DoctorCheck(name, CheckStatus.INFO, okDetail ?: dir.toString())
        } catch (_: java.nio.file.AccessDeniedException) {
            // The label is read off the BRANCH, not off the caught throwable's runtime class: this
            // clause only ever stands in for AccessDeniedException, so naming it is a compile-time
            // fact and the reflective lookup that used to produce the same six syllables is gone.
            DoctorCheck(name, CheckStatus.FAIL, "$dir is not writable (AccessDeniedException)", "chmod u+rwx $dir")
        } catch (e: java.io.IOException) {
            val why = "$dir is not writable (${SafeFailureText.render(e)})"
            DoctorCheck(name, CheckStatus.FAIL, why, "check free space: df -h $dir")
        } finally {
            probe?.let { p -> Cancellables.runCatchingCancellable { Files.deleteIfExists(p) } }
        }
    }

    /** Last-N turn outcomes from the per-head perf JSONL — "last failure: 4m ago (upstream_failed)"
     *  is the sentence doctor exists to say. Read as a bounded tail of BOTH generations (a failure
     *  that rotated into .1 minutes ago still counts); a generation that cannot be read is said,
     *  never presented as no turns. Missing/empty files = INFO (a fresh head has no turns). */
    internal fun perfTailRow(headKey: String, perfFile: Path): DoctorCheck {
        val read = files.tails(perfFile, PROBE_TAIL_BYTES)
        val rows = read.lines.takeLast(PERF_TAIL_TURNS * 2).mapNotNull(::perfRow).takeLast(PERF_TAIL_TURNS)
        val failures = rows.filter { (outcome, _) -> outcome != "ok" }
        val name = "head $headKey turns"
        val unread = read.error?.let { " (a perf file could not be read: $it)" }.orEmpty()
        return when {
            rows.isEmpty() && read.error != null ->
                DoctorCheck(name, CheckStatus.WARN, "perf file could not be read: ${read.error}", null)
            rows.isEmpty() -> DoctorCheck(name, CheckStatus.INFO, "no turns recorded yet")
            failures.isEmpty() -> DoctorCheck(name, CheckStatus.OK, "last ${rows.size} turn(s) clean$unread")
            else -> failed(name, headKey, rows.size, failures, unread)
        }
    }

    private fun failed(
        name: String,
        headKey: String,
        n: Int,
        failures: List<Pair<String, Long>>,
        unread: String,
    ): DoctorCheck {
        val (outcome, ts) = failures.last()
        val ageMin = ((System.currentTimeMillis() - ts) / MS_PER_MINUTE).coerceAtLeast(0)
        val last = "last failure: ${ageMin}m ago (${tag(outcome)})"
        val detail = "${failures.size} of last $n turn(s) failed — $last$unread"
        return DoctorCheck(name, CheckStatus.WARN, detail, "splice logs --head $headKey --tail 50")
    }

    /** One perf JSONL row -> (outcome, ts); null on a malformed line (tail readers stay tolerant). */
    internal fun perfRow(line: String): Pair<String, Long>? =
        // ast-grep-ignore: kt-no-silent-result-collapse -- 2026-09-17 (V4-112): a torn or malformed JSONL tail line is normal — the daemon appends while doctor reads — so the row is skipped and the count of readable rows is what the check reports.
        Cancellables.runCatchingCancellable {
            val obj = kotlinx.serialization.json.Json.parseToJsonElement(line).jsonObject
            val outcome = JsonScalars.str(obj, "outcome")
            if (outcome == null) null else outcome to (JsonScalars.long(obj, "ts") ?: 0L)
        }.getOrNull()

    /** An outcome is a tag from the daemon's vocabulary; a perf row is a plain file, so anything
     *  else shaped is shown as ?, never quoted. */
    private fun tag(outcome: String): String = outcome.takeIf { OUTCOME_TAG.matches(it) } ?: "?"
}

private const val PERF_TAIL_TURNS = 20

/** Enough bytes for well over 20 rows per generation (a row is under 1 KiB). */
private const val PROBE_TAIL_BYTES = 64 shl 10
private const val MS_PER_MINUTE = 60_000L
