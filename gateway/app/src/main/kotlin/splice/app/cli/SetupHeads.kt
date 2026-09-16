// NEW: the head-picking half of the setup wizard — the rows it offers, the two seams it asks
// through, and the class that adds what was ticked. Split out of SetupCommand.kt (2026-09-16): that
// file carried five types and 309 lines and the concentration wall put it in the HIGH band alone,
// which is the wall doing its job on work that had just grown. The wizard proper keeps the flow;
// this keeps the head list.
package splice.app.cli

import splice.app.TopologyLoader
import splice.app.cli.prompt.MultiSelectOutcome
import splice.app.cli.prompt.SelectOption
import splice.app.cli.prompt.WizardFrame
import splice.core.util.EnvReader
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

/** One splice add profile --yes call; production is AddCommand with a no-op restart. */
internal fun interface ProfileAdd {
    suspend operator fun invoke(name: String): Boolean
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
    fun offer(facts: SetupFacts, path: Path): List<String> {
        val catalog = profiles.catalog()
        announceExcluded(catalog)
        val tickable = tickableProfiles(catalog, installedKeys(path))
        if (tickable.isEmpty()) return emptyList()
        return pickFrom(tickable, facts)
    }

    suspend fun addAll(names: List<String>) {
        if (names.isEmpty()) return
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
    }

    private fun announceExcluded(catalog: List<AddProfile>) {
        for (profile in catalog) {
            val reason = TICK_EXCLUDED[profile.name] ?: continue
            frame.step("splice add ${profile.name} — $reason")
        }
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
                profile.name in TICK_EXCLUDED -> Unit
                profile.headKey in installed || profile.name in installed ->
                    frame.step("already installed: ${profile.name}")
                else -> tickable += profile
            }
        }
        return tickable
    }

    private fun pickFrom(tickable: List<AddProfile>, facts: SetupFacts): List<String> {
        val options = tickable.map { profile ->
            val hint = if (hasCredential(profile, facts)) "credential present" else "sign-in needed"
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
            MultiSelectOutcome.Cancelled -> emptyList()
        }
    }

    private fun hasCredential(profile: AddProfile, facts: SetupFacts): Boolean = when {
        profile.authKind in facts.spliceOwned -> true
        profile.authKind in facts.vendorCli -> true
        profile.authKind != API_KEY_KIND -> false
        else -> !env(profiles.apiKeyEnv(profile.headKey)).isNullOrBlank()
    }
}

private val TICK_EXCLUDED = mapOf(
    "api-key" to "needs a base URL; run: splice add api-key --base-url URL --name NAME",
    "claude" to "needs a name; run: splice add claude --name NAME",
)

internal const val API_KEY_KIND = "api-key"
