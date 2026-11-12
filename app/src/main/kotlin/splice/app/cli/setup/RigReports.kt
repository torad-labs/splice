// NEW: what `splice setup` reads out of rig's answers — the three JSON shapes, carrying ONLY the
// fields splice reads (rig's reports carry many more; unknown keys are ignored, so rig can grow them
// without breaking a wizard already shipped), and rig's exit codes in the operator's words. The
// contract is rig's own (v0.1.1 source, v0.1.2 from its maintainer): prepare and up print JSON on
// stdout only on success, and every failure is an exit code plus a sentence on stderr.
package splice.app.cli.setup

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import splice.core.util.Cancellables

/** rig's general failure. In `prepare` a required tool is missing and stderr names it (the compile
 *  toolchain too, with the glibc it found); in `up` a step failed, the head never came up, or
 *  (v0.1.3) the disk cannot hold what the bring-up writes, refused before the download. */
private const val RIG_EXIT_FAILED = 1

/** `up`: rig will not start the head over its port. Under `--restart` (splice never passes it) the
 *  head is serving live traffic; from v0.1.4 also a port that neither answers nor refuses. rig's
 *  sentence names which. */
private const val RIG_EXIT_BUSY = 2

/** The card: in `prepare` rig has not measured its compute capability; in `up` (v0.1.3) its VRAM is
 *  below the head's smallest tier, refused before the download. rig's sentence names which. */
private const val RIG_EXIT_CARD = 3

/** `prepare`, and `up` passing it through: the driver predates the CUDA runtime the engine needs. */
private const val RIG_EXIT_DRIVER = 4

/** How many of rig's last stderr lines a failure shows: enough for the failing step and its cause,
 *  few enough that the wizard's own lines stay on the screen. */
private const val STDERR_SHOWN = 6

/** `rig prepare --json` on exit 0. [noPrebuilt] (v0.1.2) sits beside `"prebuilt": false` when the
 *  host's glibc is below the prebuilt engine's floor, so rig will compile instead; it is a sentence
 *  for the operator, shown verbatim and never parsed. */
@Serializable
internal data class PrepareReport(val noPrebuilt: String? = null)

/** `rig up --json` on exit 0. [linger] (v0.1.2): false when linger is off and rig could not enable
 *  it; null when logind was unreadable (rig says so itself); absent on 0.1.1. */
@Serializable
internal data class UpReport(val linger: Boolean? = null)

/** `rig describe <head>` — always JSON, snake_case. */
@Serializable
internal data class DescribeReport(
    val name: String,
    val title: String,
    val dialect: String,
    @SerialName("base_url") val baseUrl: String,
    /** The window rig ADVERTISES, below the model's raw ceiling (model_ctx), so a full prompt
     *  still leaves the server room to answer. */
    @SerialName("advertise_ctx") val advertiseCtx: Long,
    @SerialName("server_facts") val serverFacts: ServerFacts,
)

/** What llama-server does that a row has to know. The two that SHAPE the row are required: guessing
 *  either would write a quirk the server contradicts. any_model_id only relaxes a check, so its
 *  absence reads as the strict answer. */
@Serializable
internal data class ServerFacts(
    @SerialName("rejects_reasoning_effort") val rejectsReasoningEffort: Boolean,
    @SerialName("slot_pinning") val slotPinning: Boolean,
    @SerialName("any_model_id") val anyModelId: Boolean = false,
)

internal class RigReports {
    private val json = Json { ignoreUnknownKeys = true }

    /** The no-prebuilt sentence, or null. A report that does not parse has no sentence to show and
     *  is not a failure: prepare already answered 0, and `rig up` runs prepare again first. */
    fun noPrebuilt(stdout: String): String? =
        Cancellables.runCatchingCancellable { json.decodeFromString<PrepareReport>(stdout) }
            .fold(onSuccess = { it.noPrebuilt }, onFailure = { null })

    /** True only for an explicit `"linger": false`. A report that does not parse reads as absent —
     *  the head is up either way (exit 0), and absent means say nothing. */
    fun lingerOff(stdout: String): Boolean =
        Cancellables.runCatchingCancellable { json.decodeFromString<UpReport>(stdout) }
            .fold(onSuccess = { it.linger == false }, onFailure = { false })

    fun describe(stdout: String): Result<DescribeReport> =
        Cancellables.runCatchingCancellable { json.decodeFromString<DescribeReport>(stdout) }
}

/** rig's non-zero exits in plain sentences, one list per verb because code 1 means a different thing
 *  in each. 3 and 4 come from either verb. */
internal class RigRefusals {

    fun prepare(run: RigRun): List<String> = card(run) ?: when (run.exit) {
        // As-is: rig's own stderr names what is missing, comma-joined, and says why when it is the
        // compile toolchain. A paraphrase could only lose a name.
        RIG_EXIT_FAILED -> run.stderr.lines().filter { it.isNotBlank() }
            .ifEmpty { listOf("rig prepare found a required tool missing") }
        else -> listOf("rig prepare exited ${run.exit}") + run.tail(STDERR_SHOWN)
    }

    fun up(run: RigRun): List<String> = card(run) ?: when (run.exit) {
        // The server writes to rig's log file, not the journal (the unit's journal holds only systemd's
        // lines), and rig refuses a full disk before the head starts, so the log is offered, not presumed.
        RIG_EXIT_FAILED -> listOf("rig up $RIG_HEAD failed:") + run.tail(STDERR_SHOWN) +
            "if the head started, its log: ~/.local/share/rig/local/logs/$RIG_HEAD.log"
        RIG_EXIT_BUSY -> run.tail(STDERR_SHOWN).ifEmpty { listOf("rig refused to restart a head that is serving") }
        else -> listOf("rig up $RIG_HEAD exited ${run.exit}") + run.tail(STDERR_SHOWN)
    }

    private fun card(run: RigRun): List<String>? = when (run.exit) {
        // As-is, as prepare's exit 1: the sentence names the card and why (its compute capability,
        // or its VRAM against the head's floor), and a paraphrase could only lose the numbers.
        RIG_EXIT_CARD -> run.tail(STDERR_SHOWN).ifEmpty { listOf("this card is not one rig supports yet") }
        RIG_EXIT_DRIVER ->
            listOf("the NVIDIA driver is older than the CUDA runtime the engine needs; update the driver")
        else -> null
    }
}
