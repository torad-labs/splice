// NEW: the head-picking half of the setup wizard — the rows it offers, the two seams it asks
// through, and the class that adds what was ticked. Split out of SetupCommand.kt (2026-09-16): that
// file carried five types and 309 lines and the concentration wall put it in the HIGH band alone,
// which is the wall doing its job on work that had just grown. The wizard proper keeps the flow;
// this keeps the head list.
package splice.app.cli.setup

import splice.configuration.add.AddProfile
import splice.configuration.add.AddProfiles
import splice.configuration.add.DaemonRestart
import splice.core.config.KeyStore
import splice.core.config.KeyStorePath
import splice.core.topology.API_KEY_WIRE
import splice.core.util.EnvReader
import splice.terminal.ConsolePresence
import splice.terminal.MultiSelectOutcome
import splice.terminal.SelectOption
import splice.terminal.WizardFrame
import splice.topology.TopologyLoader
import java.nio.file.Files
import java.nio.file.Path

/** One head the wizard can still sign in. Shared with SetupCommand.kt across the 2026-09-16
 *  split, hence internal rather than file-private. */
internal data class PendingOAuthHead(val key: String, val command: String)

/** Multi-select over AddProfiles names. */
internal fun interface HeadPicker {
    operator fun invoke(
        options: List<SelectOption<String>>,
        initiallySelected: Set<String>,
    ): MultiSelectOutcome<String>
}

/** The models to write for one head, from its profile's own (SetupHeadChoices). */
internal fun interface ModelPicker {
    operator fun invoke(
        question: String,
        options: List<SelectOption<String>>,
        initiallySelected: Set<String>,
    ): MultiSelectOutcome<String>
}

/** One splice add profile --yes call; production is AddCommand with a no-op restart. */
internal fun interface ProfileAdd {
    suspend operator fun invoke(name: String): Boolean

    /** The checkup once the last head is added. An add that is not the real one has none to run. */
    fun checkup(env: EnvReader) = Unit
}

/** Tick-list + install loop so SetupCommand stays under the function ceiling. */
internal class SetupHeads(
    private val profiles: AddProfiles,
    private val pick: HeadPicker,
    private val add: ProfileAdd,
    private val restart: DaemonRestart,
    private val hasConsole: ConsolePresence,
    private val env: EnvReader,
    private val frame: WizardFrame,
) {
    /** The plans the person ticked, none when every row is installed already; null when they left the list with
     *  Escape or Ctrl-C, which ends setup. */
    fun offer(facts: SetupFacts, path: Path): List<String>? {
        val catalog = profiles.catalog()
        val tickable = tickableProfiles(catalog, installedKeys(path))
        val picked = if (tickable.isEmpty()) emptyList() else pickFrom(tickable, facts)
        if (picked != null) announceByCommand(catalog)
        return picked
    }

    /** Returns what LANDED, which is a different list from what was ticked: `splice add` refuses a
     *  command collision or a topology that stops parsing. The Claude lane is applied from that
     *  list and only after the restart below, because wrapping reads the LIVE `claude`
     *  head's own spec — a wrap posted before the daemon carries the head it was just handed is
     *  refused with "the 'claude' head is not configured", which is the daemon being right. */
    suspend fun addAll(names: List<String>): List<String> {
        if (names.isEmpty()) return emptyList()
        val landed = mutableListOf<String>()
        val failed = mutableListOf<String>()
        for (name in names) {
            if (add(name)) landed += name else failed += name
        }
        if (landed.isNotEmpty()) restart()
        val parts = mutableListOf<String>()
        if (landed.isNotEmpty()) parts += "added ${landed.joinToString(", ")}"
        if (failed.isNotEmpty()) parts += "failed ${failed.joinToString(", ")}"
        println(parts.joinToString("; "))
        return landed
    }

    /** The rows the list cannot add, after it, each as what it is and the command that adds it. Until Nov 11, 2026
     *  they came first and read as two failures ("splice add api-key: needs a base URL; run: ...") on a screen where
     *  nothing had been tried yet. */
    private fun announceByCommand(catalog: List<AddProfile>) {
        val named = catalog.map { it.name }.toSet()
        val rows = BY_COMMAND.filterKeys { it in named }.values
        if (rows.isEmpty()) return
        frame.step("Two more kinds are added by command, since each needs an address of its own:")
        rows.forEach { frame.step("  $it") }
    }

    private fun installedKeys(path: Path): Set<String> = if (Files.exists(path)) {
        TopologyLoader.parse(Files.readString(path)).heads.keys
    } else {
        emptySet()
    }

    private fun tickableProfiles(catalog: List<AddProfile>, installed: Set<String>): List<AddProfile> {
        val tickable = mutableListOf<AddProfile>()
        for (profile in catalog) {
            when {
                profile.name in BY_COMMAND -> Unit
                profile.head.key in installed || profile.name in installed ->
                    frame.step("already installed: ${profile.name}")
                else -> tickable += profile
            }
        }
        // Claude's own login first: it is what Claude Code runs on before splice, and the list opens the wizard.
        return tickable.sortedByDescending { it.name == CLAUDE_PROFILE }
    }

    private fun pickFrom(tickable: List<AddProfile>, facts: SetupFacts): List<String>? {
        val options = tickable.map { profile ->
            val hint = listOfNotNull(profile.summary, held(profile, facts)).joinToString(" \u00b7 ")
            SelectOption(profile.name, profile.name, hint)
        }
        val credited = tickable.filter { hasCredential(it, facts) }.map { it.name }.toSet()
        val initial = if (hasConsole()) {
            options.map { it.value }.filter { it in credited }.toSet()
        } else {
            emptySet()
        }
        return when (val picked = pick(options, initial)) {
            is MultiSelectOutcome.Chosen -> picked.values
            MultiSelectOutcome.Cancelled -> null
        }
    }

    /** A row starts ticked when splice already holds its sign-in or key, or the vendor's own app is signed in on this
     *  machine, which says the person has that plan. */
    private fun hasCredential(profile: AddProfile, facts: SetupFacts): Boolean =
        held(profile, facts) != null || profile.provider.authKind in facts.vendorCli

    /** What splice already holds for a row, said after the row's own line; null when it holds nothing yet, which the
     *  row's line covers by naming how it signs in. The Claude row holds nothing of its own: it forwards the person's
     *  Claude Code login. Until Nov 11, 2026 every row without a credential read "sign-in needed", the Claude row and
     *  the API-key rows among them, a key saved by `splice key set` did not count, and a row whose vendor app was
     *  signed in read "credential present" before splice asked it to sign in. */
    private fun held(profile: AddProfile, facts: SetupFacts): String? {
        val kind = profile.provider.authKind
        val keyName = profiles.apiKeyEnv(profile.head.key)
        return when {
            kind in facts.spliceOwned -> "signed in"
            kind != API_KEY_KIND -> null
            !env(keyName).isNullOrBlank() -> "key found in $keyName"
            KeyStore(KeyStorePath.defaultPath(env)).presence(keyName) == KeyStore.Presence.Stored -> "key saved"
            else -> null
        }
    }
}

// V4-175: `claude` LEFT this map. Its reason — "needs a name" — was never true of that row:
// AddPrepare.kt:42 takes `args.name ?: profile.head.key` and the catalogue gives it `claude`,
// so `splice add claude --yes` has always worked unaided. The wrong reason is what kept the lane
// choice off the wizard entirely, which is the half of V4-175 the operator asked for. `api-key`
// and `local` stay excluded: both need a name, base URL and model before they can be added, and
// the tick list cannot collect those values. Each is said as what it is and the command that adds it.
private val BY_COMMAND = mapOf(
    "api-key" to "another service that takes an API key: splice add api-key --name NAME --base-url URL",
    "local" to "a model server you run: splice add local --name NAME --base-url URL --model ID:WINDOW",
)

// The literal itself lives in :core beside the registered kinds (API_KEY_WIRE, 2026-09-22); this
// name stays because the call sites here compare a PROFILE's declared authKind string, not an
// AuthConfig, so AuthConfig.isApiKey is not the accessor they can use.
internal const val API_KEY_KIND = API_KEY_WIRE
