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
)

internal class AddPrepare(
    private val output: TerminalOutput,
    private val checks: AddChecks,
    prompt: AddPrompter,
) {
    private val profiles = AddProfiles()
    private val modelRows = AddModelRows(output, prompt)

    fun candidate(args: AddArgs, env: EnvReader): AddCandidate? {
        val profile = args.profile?.let(profiles::find) ?: return usage()
        val path = TopologyLoader.configPath(env)
        val current = TopologyLoader.loadOrMaterialize(path)
        val existing = Files.readString(path).trimEnd('\n') + "\n"
        val key = args.name ?: profile.headKey
        val resolved = resolved(args, profile, key) ?: return null
        val problem = keyProblem(resolved, current, key) ?: valueProblem(resolved) ?: liveProblem(args, resolved)
        val appended = if (problem == null) profiles.toml(resolved, key, nextPort(current)) else ""
        val parsed = if (problem == null) checks.parses(existing + appended) else Result.failure(AddRefused(problem))
        return parsed.fold(
            onSuccess = { topology ->
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
                )
            },
            onFailure = { e ->
                output.line("splice add: ${refusal(e)}")
                null
            },
        )
    }

    /** The profile with the operator's flags and model rows applied, or null after the refusal was
     *  printed (a prompted window never became valid). */
    private fun resolved(args: AddArgs, profile: AddProfile, key: String): AddProfile? = try {
        profile.copy(
            baseUrl = args.baseUrl ?: profile.baseUrl.orEmpty(),
            command = args.command ?: profile.command.ifEmpty { "claude-$key" },
            models = modelRows.resolve(args, profile),
        )
    } catch (refused: AddRefused) {
        // SAFE-RENDER-EXEMPT[2026-09-15]: AddRefused is constructed only by splice with a fixed
        // operator sentence, never from upstream or file content. The single construction site is
        // AddModels.kt line 58 (a context window must be a positive integer in tokens). The
        // exemption stops being true the day an AddRefused is built from a caught throwable.
        output.line("splice add: ${refused.message}")
        null
    }

    private fun usage(): AddCandidate? {
        output.line("splice add: which profile? one of:")
        profiles.describe().forEach { output.line("  $it") }
        output.line(
            "  usage: splice add <profile> [--name KEY] [--base-url URL] [--model ID:WINDOW ...] [--command NAME]",
        )
        output.line("         [--live] [--yes]")
        return null
    }

    private fun refusal(e: Throwable): String = when (e) {
        is AddRefused -> e.message.orEmpty()
        else -> "the candidate topology does not parse: ${SafeFailureText.render(e)}"
    }

    private fun keyProblem(profile: AddProfile, current: Topology, key: String): String? = when {
        !KEY_RE.matches(key) -> "--name is required for '${profile.name}' (lowercase letters, digits, dashes)"
        key in current.providers || key in current.heads -> "'$key' is already configured — pick another --name"
        // A head with no explicit command launches as its own key (Topology.resolveHeadKeys), so that is
        // the name a new command must not take either.
        current.heads.any { (headKey, head) -> (head.claude.command ?: headKey) == profile.command } ->
            "command '${profile.command}' already belongs to a head"
        else -> null
    }

    /** `--live` speaks plain HTTP with a splice-held key; a browser-OAuth or client-auth profile has
     *  no such turn to run, and a flag that would silently do nothing is refused instead. */
    private fun liveProblem(args: AddArgs, profile: AddProfile): String? = when {
        !args.live || profile.dialect == OPENAI_CHAT_DIALECT -> null
        else ->
            "--live is only supported for api-key profiles; '${profile.name}' is exercised by its first launch, " +
                "then splice doctor — drop --live"
    }

    /** [profile] here is the resolved one: base URL, command and models already filled in. */
    private fun valueProblem(profile: AddProfile): String? = when {
        profile.baseUrl.isNullOrEmpty() -> "--base-url is required for '${profile.name}'"
        modelRows.problem(profile.models) != null -> modelRows.problem(profile.models)
        !addValuePattern.matches(profile.baseUrl) || !addValuePattern.matches(profile.command) ->
            "values must not contain quotes"
        else -> null
    }

    private fun nextPort(current: Topology): Int {
        val taken = current.heads.values.map { it.port } + listOfNotNull(current.daemon.controlPort)
        val floor = maxOf(taken.maxOrNull() ?: 0, FIRST_HEAD_PORT - 1) + 1
        return generateSequence(floor) { it + 1 }.first { it !in taken }
    }
}

/** A refusal decided before the candidate was parsed; its message is the whole explanation. Public
 *  since LAYOUT-01: the CLI's guard renders it verbatim, as a refusal rather than a breakage. */
public class AddRefused(message: String) : RuntimeException(message)
