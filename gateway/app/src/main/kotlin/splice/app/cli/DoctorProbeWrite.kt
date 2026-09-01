// NEW: (JW-17, split from DoctorCommand.kt — the file sits at detekt's function budget) the
// state/log writability probe. Three subsystems (daemon.log, config persistence, usage/perf/
// compact appends) degrade silently on an unwritable ~/.claude-codex; doctor printed the path
// but never touched it.
package splice.app.cli

import kotlinx.serialization.json.jsonObject
import splice.core.util.Cancellables
import splice.core.util.JsonScalars
import splice.core.util.SafeFailureText
import java.nio.file.Files
import java.nio.file.Path

/** The writability probe as a constructed collaborator rather than a free function (Kotlin style
 *  law, 2026-08-15: main sources carry no top-level functions). Stateless — doctor builds one and
 *  asks it; the member keeps the old function's name so every historical grep still lands. */
internal class DoctorProbeWrite {

    /** JW-17: write-and-delete a dot-prefixed probe in [dir] (created first, as the daemon would).
     *  OK carries [okDetail] (the path, or a richer label); a failure is a FAIL whose fix is chosen
     *  by cause — AccessDenied wants chmod, anything else (typically no space) wants df. Non-mutating
     *  in spirit: the probe is removed in a finally. */
    internal fun writableProbe(name: String, dir: Path, okDetail: String? = null): DoctorCheck {
        val probe = dir.resolve(".splice-doctor-write-probe")
        return try {
            Files.createDirectories(dir)
            Files.writeString(probe, "probe")
            DoctorCheck(name, CheckStatus.INFO, okDetail ?: dir.toString())
        } catch (_: java.nio.file.AccessDeniedException) {
            // The label is read off the BRANCH, not off the caught throwable's runtime class: this
            // clause only ever stands in for AccessDeniedException, so naming it is a compile-time
            // fact and the reflective lookup that used to produce the same six syllables is gone.
            DoctorCheck(name, CheckStatus.FAIL, "$dir is not writable (AccessDeniedException)", "chmod u+rwx $dir")
        } catch (e: java.io.IOException) {
            DoctorCheck(
                name,
                CheckStatus.FAIL,
                "$dir is not writable (${SafeFailureText.render(e)})",
                "check free space: df -h $dir",
            )
        } finally {
            Cancellables.runCatchingCancellable { Files.deleteIfExists(probe) }
        }
    }

    /** Last-N turn outcomes from the per-head perf JSONL — "last failure: 4m ago (upstream_failed)"
     *  is the sentence doctor exists to say. Missing/empty file = INFO (a fresh head has no turns). */
    internal fun perfTailRow(headKey: String, perfFile: Path): DoctorCheck {
        val rows = Cancellables.runCatchingCancellable {
            Files.readAllLines(perfFile).takeLast(PERF_TAIL_TURNS).mapNotNull { line -> perfRow(line) }
        }.getOrNull().orEmpty()
        if (rows.isEmpty()) return DoctorCheck("head $headKey turns", CheckStatus.INFO, "no turns recorded yet")
        val failures = rows.filter { (outcome, _) -> outcome != "ok" }
        if (failures.isEmpty()) {
            return DoctorCheck("head $headKey turns", CheckStatus.OK, "last ${rows.size} turn(s) clean")
        }
        val (outcome, ts) = failures.last()
        val ageMin = ((System.currentTimeMillis() - ts) / MS_PER_MINUTE).coerceAtLeast(0)
        return DoctorCheck(
            "head $headKey turns",
            CheckStatus.WARN,
            "${failures.size} of last ${rows.size} turn(s) failed — last failure: ${ageMin}m ago ($outcome)",
            "splice logs --head $headKey --tail 50",
        )
    }

    /** One perf JSONL row -> (outcome, ts); null on a malformed line (tail readers stay tolerant). */
    internal fun perfRow(line: String): Pair<String, Long>? = Cancellables.runCatchingCancellable {
        val obj = kotlinx.serialization.json.Json.parseToJsonElement(line).jsonObject
        val outcome = JsonScalars.str(obj, "outcome")
        if (outcome == null) null else outcome to (JsonScalars.long(obj, "ts") ?: 0L)
    }.getOrNull()
}

private const val PERF_TAIL_TURNS = 20
private const val MS_PER_MINUTE = 60_000L
