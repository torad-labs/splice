// NEW: v0.4.0 FEATURES.md §1 — everything `splice add` decides BEFORE its first side effect — the
// profile, the key, the models, the refusals (taken key or command, missing base URL, quotes), the
// next free port, and the candidate topology parsed from the operator's file plus the appended
// tables. Split from AddCommand.kt (concentration, 2026-09-13); the model rows live in AddModelRows
// (concentration, 2026-09-14).
package splice.configuration.add

import splice.core.terminal.TerminalOutput
import splice.core.topology.ProviderConfig
import splice.core.topology.Topology
import splice.core.util.EnvReader
import splice.core.util.SafeFailureText
import splice.topology.TopologyLoader
import java.nio.file.Files
import java.nio.file.Path

private const val FIRST_HEAD_PORT = 3099
private val KEY_RE = Regex("[a-z0-9][a-z0-9-]*")
private const val OPENAI_CHAT_DIALECT = "openai-chat"

/** Everything decided before the first side effect. */
internal data class AddCandidate(
    val path: Path,
    val existing: String,
    val appended: String,
    val topology: Topology,
    val key: String,
    val command: String,
    val provider: ProviderConfig,
    val models: List<String>,
    val args: AddArgs,
    /** The resolved profile the tables were rendered from: its origin titles the run and its
     *  listAuthoritative decides the models check. */
    val resolved: AddProfile,
)

/** What preparing an add decided, before any side effect: a candidate, or why there is none. */
internal sealed class AddPrepared {
    data class Ready(val candidate: AddCandidate) : AddPrepared()

    /** [conflict] is a refusal the operator's CURRENT file causes (a taken key or command, a file that
     *  will not parse with the new tables), as against one the request itself carries. */
    data class Refused(val refusal: AddRefusal, val conflict: Boolean) : AddPrepared()

    data object UnknownProfile : AddPrepared()
}

internal class AddPrepare(
    private val output: TerminalOutput,
    private val checks: AddChecks,
    prompt: AddPrompter,
) {
    private val profiles = AddProfiles()
    private val modelRows = AddModelRows(output, prompt)
    private val texts = AddRefusalText()

    /** The CLI's reading: the refusal or the usage printed, and null. */
    fun candidate(args: AddArgs, env: EnvReader): AddCandidate? = when (val prepared = prepare(args, env)) {
        is AddPrepared.Ready -> prepared.candidate
        is AddPrepared.Refused -> null.also { output.line("splice add: ${texts.cli(prepared.refusal)}") }
        AddPrepared.UnknownProfile -> usage()
    }

    /** V4-220: the same decisions as a value, for a caller that answers them rather than prints them. */
    fun prepare(args: AddArgs, env: EnvReader): AddPrepared {
        val profile = args.profile?.let(profiles::find) ?: return AddPrepared.UnknownProfile
        val key = args.name ?: profile.headKey
        return when (val rows = modelRows.resolve(args, profile)) {
            is AddRows.Resolved -> assembled(args, applied(args, profile, key, rows.models), key, env)
            is AddRows.Refused -> AddPrepared.Refused(AddRefusal.Models(rows.problem), conflict = false)
        }
    }

    /** A profile a local runtime DESCRIBED (RuntimeHeadAdd), already resolved: no flags to apply and
     *  nothing to prompt for, then the same refusals, render and parse as a catalogue profile. */
    fun described(profile: AddProfile, env: EnvReader): AddCandidate? {
        val prepared = assembled(AddArgs(profile = profile.name, yes = true), profile, profile.headKey, env)
        if (prepared is AddPrepared.Refused) output.line("splice add: ${texts.cli(prepared.refusal)}")
        return (prepared as? AddPrepared.Ready)?.candidate
    }

    private fun assembled(args: AddArgs, resolved: AddProfile, key: String, env: EnvReader): AddPrepared {
        val path = TopologyLoader.configPath(env)
        val current = TopologyLoader.loadOrMaterialize(path)
        val existing = Files.readString(path).trimEnd('\n') + "\n"
        val conflict = keyConflict(resolved, current, key)
        val problem = conflict ?: keyProblem(resolved, key) ?: valueProblem(resolved) ?: liveProblem(args, resolved)
        if (problem != null) return AddPrepared.Refused(problem, conflict = conflict != null)
        val appended = profiles.toml(resolved, key, nextPort(current))
        return checks.parses(existing + appended).fold(
            onSuccess = { topology ->
                AddPrepared.Ready(
                    AddCandidate(
                        path = path,
                        existing = existing,
                        appended = appended,
                        topology = topology,
                        key = key,
                        command = resolved.command,
                        provider = topology.providers.getValue(key),
                        models = resolved.models.map { it.id },
                        args = args,
                        resolved = resolved,
                    ),
                )
            },
            onFailure = { e ->
                AddPrepared.Refused(AddRefusal.Unparseable(SafeFailureText.render(e)), conflict = true)
            },
        )
    }

    /** The profile with the operator's flags and model rows applied. */
    private fun applied(args: AddArgs, profile: AddProfile, key: String, models: List<AddModel>): AddProfile =
        profile.copy(
            baseUrl = args.baseUrl ?: profile.baseUrl.orEmpty(),
            command = args.command ?: profile.command.ifEmpty { "claude-$key" },
            models = models,
        )

    private fun usage(): AddCandidate? {
        output.line("splice add: which profile? one of:")
        profiles.describe().forEach { output.line("  $it") }
        output.line(
            "  usage: splice add <profile> [--name KEY] [--base-url URL] [--model ID:WINDOW ...] [--command NAME]",
        )
        output.line("         [--live] [--yes]")
        return null
    }

    private fun keyProblem(profile: AddProfile, key: String): AddRefusal? = when {
        KEY_RE.matches(key) -> null
        else -> AddRefusal.NameRequired(profile.name)
    }

    /** What the operator's current file already holds; checked first, as before the split. */
    private fun keyConflict(profile: AddProfile, current: Topology, key: String): AddRefusal? = when {
        !KEY_RE.matches(key) -> null
        key in current.providers || key in current.heads -> AddRefusal.KeyTaken(key)
        // A head with no explicit command launches as its own key (Topology.resolveHeadKeys), so that is
        // the name a new command must not take either.
        current.heads.any { (headKey, head) -> (head.claude.command ?: headKey) == profile.command } ->
            AddRefusal.CommandTaken(profile.command)
        else -> null
    }

    /** `--live` speaks plain HTTP with a splice-held key; a browser-OAuth or client-auth profile has
     *  no such turn to run, and a flag that would silently do nothing is refused instead. */
    private fun liveProblem(args: AddArgs, profile: AddProfile): AddRefusal? = when {
        !args.live || profile.dialect == OPENAI_CHAT_DIALECT -> null
        else -> AddRefusal.LiveUnsupported(profile.name)
    }

    /** [profile] here is the resolved one: base URL, command and models already filled in. */
    private fun valueProblem(profile: AddProfile): AddRefusal? {
        val rows = modelRows.problem(profile.models)
        return when {
            profile.baseUrl.isNullOrEmpty() -> AddRefusal.BaseUrlRequired(profile.name)
            rows != null -> AddRefusal.Models(rows)
            !addValuePattern.matches(profile.baseUrl) || !addValuePattern.matches(profile.command) ->
                AddRefusal.QuotedValue
            else -> null
        }
    }

    private fun nextPort(current: Topology): Int {
        val taken = current.heads.values.map { it.port } + listOfNotNull(current.daemon.controlPort)
        val floor = maxOf(taken.maxOrNull() ?: 0, FIRST_HEAD_PORT - 1) + 1
        return generateSequence(floor) { it + 1 }.first { it !in taken }
    }
}

/** `splice add-model`'s refusal (HeadModelArray, AddModelVerb); its message is the whole explanation. `splice
 *  add` decides AddRefusal values instead (V4-220). Public since LAYOUT-01: the CLI's guard renders it
 *  verbatim, as a refusal rather than a breakage. */
public class AddRefused(message: String) : RuntimeException(message)
