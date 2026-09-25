// NEW: `splice setup`'s optional local-model step. On a Linux x86_64 machine with an NVIDIA card it
// offers to hand the card to rig — rig installs itself into ~/.local/share/rig, downloads
// bonsai-2-27b (about 8 GB), builds the engine and serves it on 127.0.0.1 — and then adds a `bonsai`
// head that points at what rig DESCRIBES. splice itself still never downloads a model or manages a
// runtime; rig does, and the wizard only asks it.
//
// THE OFFER IS NARROW ON PURPOSE. It is asked only with someone watching (the non-TTY path takes
// every default and must never start an 8 GB download), only where rig ships (Linux x86_64), only
// with an NVIDIA card nvidia-smi can name, and only when no `bonsai` is configured yet. Default NO.
//
// IT NEVER FAILS THE SETUP. The heads and wrappers are already installed when it runs; anything that
// goes wrong here is said in a line or two — with the command to retry by hand — and the wizard
// carries on to sign-in. In its own file because SetupCommand.kt is watched by the concentration wall.
package splice.app.cli.setup

import splice.app.AddWiring
import splice.configuration.add.DaemonRestart
import splice.configuration.add.RuntimeHead
import splice.core.terminal.TerminalOutput
import splice.core.topology.Dialect
import splice.core.topology.DialectWires
import splice.core.util.Cancellables
import splice.core.util.EnvReader
import splice.core.util.SafeFailureText
import splice.topology.TopologyLoader
import java.nio.file.Files
import java.nio.file.Path

/** What happens, in the words the question and the Summary both use. */
private const val WHAT_HAPPENS =
    "rig installs into ~/.local/share/rig, downloads $RIG_HEAD (about 8 GB; about 18 GB of disk in all) " +
        "and serves it on this machine"

private const val RETRY = "retry by hand: rig up $RIG_HEAD, then splice setup again"

/** How much of one rig progress line the spinner shows: a step and its detail fit, and an error
 *  line long enough to wrap would redraw as two rows the spinner cannot erase. */
private const val PROGRESS_SHOWN = 60

/** [env] and [restart] are SetupCommand's own, threaded into the production defaults: rig is looked
 *  up on the wizard's PATH, and the add restarts the daemon through the wizard's restart. */
internal class SetupLocalModel(
    private val prompts: SetupPrompts,
    env: EnvReader,
    restart: DaemonRestart,
    private val rig: Rig = ProcessRig(env),
    private val gpu: GpuProbe = NvidiaSmi(),
    private val platform: HostPlatform = HostPlatform(
        System.getProperty("os.name").orEmpty(),
        System.getProperty("os.arch").orEmpty(),
    ),
    private val add: LocalHeadAdd = LocalHeadAdd { head -> AddWiring.add(restart).addRuntime(head, env) },
    private val out: TerminalOutput = TerminalOutput(::println),
) {
    private val reports = RigReports()
    private val refusals = RigRefusals()

    /** Asked after the head tick-list, before the Summary. True only when the operator said yes. The
     *  console and platform come first so a headless run never even spawns nvidia-smi. */
    fun offer(path: Path): Boolean = when {
        !prompts.hasConsole() || !platform.rigSupported() -> false
        configured(path) -> false.also { prompts.frame.step("local model: '$LOCAL_KEY' is already configured") }
        else -> gpu().firstOrNull()?.let { card ->
            prompts.frame.confirm("Run a local model on your $card? $WHAT_HAPPENS", false)
        } ?: false
    }

    /** The Summary's one line, so "Install now?" covers the download too. */
    fun summary(chosen: Boolean): List<String> =
        if (chosen) listOf("Local model: $WHAT_HAPPENS; head '$LOCAL_KEY' as $LOCAL_COMMAND") else emptyList()

    /** After the heads were added, before sign-in. Never throws past the wizard. */
    suspend fun install(chosen: Boolean) {
        if (!chosen) return
        Cancellables.runCatchingBestEffort { ensureRig() && prepared() && served() && added() }
            .onFailure { stopped(listOf(SafeFailureText.render(it))) }
    }

    private fun configured(path: Path): Boolean = Files.exists(path) &&
        TopologyLoader.parse(Files.readString(path)).let { LOCAL_KEY in it.providers || LOCAL_KEY in it.heads }

    /** `rig --version` answers, or rig's installer runs — its exact command shown first. */
    private fun ensureRig(): Boolean {
        if (rig.version().exit == 0) return true
        prompts.frame.step("rig is not installed; installing it: $RIG_INSTALL")
        val installed = spun("Installing rig", "rig installed") { rig.install() }
        val version = if (installed.exit == 0) rig.version() else installed
        return when {
            installed.exit != 0 -> stopped(listOf("rig's installer failed:") + installed.tail(STDERR_LINES))
            version.exit != 0 -> stopped(listOf("rig installed, but rig --version fails:") + version.tail(STDERR_LINES))
            else -> true
        }
    }

    private fun prepared(): Boolean {
        val run = spun("Checking the card (rig prepare)", "rig prepare: the card is ready") { rig.prepare() }
        if (run.exit != 0) return stopped(refusals.prepare(run) + RETRY)
        // rig will compile rather than install its prebuilt engine: say why, in rig's words.
        reports.noPrebuilt(run.stdout)?.let { out.line("  $it") }
        return true
    }

    /** `rig up`, its `== <step>` lines fed to the spinner as they arrive: prepare, fetch, build,
     *  derive, unit, start — plus the one-line detail rig writes under a step. */
    private fun served(): Boolean {
        val label = "rig up $RIG_HEAD"
        val progress = RigProgress { line ->
            val step = line.removePrefix("== ").trim().take(PROGRESS_SHOWN)
            if (step.isNotEmpty()) prompts.spinner.update("$label: $step")
        }
        val run = spun(label, "$RIG_HEAD is serving on this machine") { rig.up(RIG_HEAD, progress) }
        if (run.exit != 0) return stopped(refusals.up(run))
        // Only an explicit false: null means logind was unreadable and rig already said so on stderr.
        if (reports.lingerOff(run.stdout)) {
            out.line("  note: $RIG_HEAD stops when you log out; keep it running: sudo loginctl enable-linger \$USER")
        }
        return true
    }

    /** `rig describe`, then the head through `splice add`'s machinery. */
    private suspend fun added(): Boolean {
        val head = described() ?: return false
        val ok = add(head)
        if (!ok) {
            val stop = "systemctl --user stop rig-$RIG_HEAD.service"
            out.line("  $RIG_HEAD keeps serving at ${head.baseUrl}; stop it with: $stop")
        }
        return ok
    }

    private fun described(): RuntimeHead? {
        val run = rig.describe(RIG_HEAD)
        if (run.exit != 0) {
            stopped(listOf("rig describe $RIG_HEAD failed:") + run.tail(STDERR_LINES))
            return null
        }
        return reports.describe(run.stdout).fold(
            onSuccess = { head(it) },
            onFailure = { e ->
                stopped(listOf("rig describe answered a shape splice cannot read: ${SafeFailureText.render(e)}"))
                null
            },
        )
    }

    /** The row's facts. reasoning_effort in splice means EMIT the field (QuirksConfig), so it is the
     *  NEGATION of the server rejecting it. */
    private fun head(described: DescribeReport): RuntimeHead? {
        if (described.dialect != DialectWires.name(Dialect.OPENAI_CHAT)) {
            stopped(listOf("rig describes $RIG_HEAD as '${described.dialect}'; the local head speaks openai-chat only"))
            return null
        }
        return RuntimeHead(
            key = LOCAL_KEY,
            describedBy = "rig describe $RIG_HEAD",
            baseUrl = described.baseUrl,
            modelId = described.name,
            modelLabel = described.title,
            contextWindow = described.advertiseCtx,
            emitReasoningEffort = !described.serverFacts.rejectsReasoningEffort,
            slotAffinity = described.serverFacts.slotPinning,
            anyModelId = described.serverFacts.anyModelId,
        )
    }

    /** One rig call under the wizard's spinner, which is stopped whatever the call does. */
    private inline fun spun(label: String, done: String, call: () -> RigRun): RigRun {
        prompts.spinner.start(label)
        val run = Cancellables.runCatchingBestEffort(call)
            .getOrElse { RigRun(STEP_THREW, "", SafeFailureText.render(it)) }
        if (run.exit == 0) prompts.spinner.stop(done) else prompts.spinner.fail("$label: stopped")
        return run
    }

    private fun stopped(lines: List<String>): Boolean {
        out.line("  local model not set up: ${lines.firstOrNull().orEmpty()}")
        lines.drop(1).forEach { out.line("    $it") }
        return false
    }
}

/** How many of rig's last stderr lines a failure outside prepare/up shows (RigRefusals owns theirs):
 *  the install and describe failures are one sentence, so a short tail carries it. */
private const val STDERR_LINES = 4

/** Exit code of a rig call that THREW before answering (an interrupted wait, a stream that broke):
 *  no process code, so one no command uses — a negative number cannot come from a real exit. */
private const val STEP_THREW = -1
