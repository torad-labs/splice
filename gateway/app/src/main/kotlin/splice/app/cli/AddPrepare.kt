// NEW: v0.4.0 FEATURES.md §1 — everything `splice add` decides BEFORE its first side effect — the
// profile, the key, the models, the refusals (taken key or command, missing base URL, quotes), the
// next free port, and the candidate topology parsed from the operator's file plus the appended
// tables. Split from AddCommand.kt (concentration, 2026-09-13).
package splice.app.cli

import splice.app.TopologyLoader
import splice.core.topology.ProviderConfig
import splice.core.topology.Topology
import splice.core.util.EnvReader
import splice.core.util.SafeFailureText
import java.nio.file.Files
import java.nio.file.Path

private const val FIRST_HEAD_PORT = 3099
private const val DEFAULT_WINDOW = 128_000L
private const val MAX_PROMPTED_MODELS = 8
private val KEY_RE = Regex("[a-z0-9][a-z0-9-]*")
private val VALUE_RE = Regex("[^\"\\\\\\p{Cntrl}]+")

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

internal class AddPrepare(private val checks: AddChecks, private val prompt: AddPrompter) {
    private val profiles = AddProfiles()

    fun candidate(args: AddArgs, env: EnvReader): AddCandidate? {
        val profile = args.profile?.let(profiles::find) ?: return usage()
        val path = TopologyLoader.configPath(env)
        val current = TopologyLoader.loadOrMaterialize(path)
        val existing = Files.readString(path).trimEnd('\n') + "\n"
        val key = args.name ?: profile.headKey
        val resolved = profile.copy(
            baseUrl = args.baseUrl ?: profile.baseUrl.orEmpty(),
            command = args.command ?: profile.command.ifEmpty { "claude-$key" },
            models = models(args, profile),
        )
        val problem = keyProblem(resolved, current, key) ?: valueProblem(resolved)
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
                println("splice add: ${refusal(e)}")
                null
            },
        )
    }

    private fun usage(): AddCandidate? {
        println("splice add: which profile? one of:")
        profiles.describe().forEach { println("  $it") }
        println("  usage: splice add <profile> [--name KEY] [--base-url URL] [--model ID:WINDOW ...] [--command NAME]")
        println("         [--live] [--yes]")
        return null
    }

    private fun refusal(e: Throwable): String = when (e) {
        is AddRefused -> e.message.orEmpty()
        else -> "the candidate topology does not parse: ${SafeFailureText.render(e)}"
    }

    private fun keyProblem(profile: AddProfile, current: Topology, key: String): String? = when {
        !KEY_RE.matches(key) -> "--name is required for '${profile.name}' (lowercase letters, digits, dashes)"
        key in current.providers || key in current.heads -> "'$key' is already configured — pick another --name"
        current.heads.values.any { (it.claude.command ?: "") == profile.command } ->
            "command '${profile.command}' already belongs to a head"
        else -> null
    }

    /** [profile] here is the resolved one: base URL, command and models already filled in. */
    private fun valueProblem(profile: AddProfile): String? {
        val quoted = profile.models.any { !VALUE_RE.matches(it.id) || !VALUE_RE.matches(it.label) }
        return when {
            profile.baseUrl.isNullOrEmpty() -> "--base-url is required for '${profile.name}'"
            profile.models.isEmpty() -> "no models: pass --model <id>:<context_window> (repeatable)"
            quoted -> "model ids must not contain quotes"
            !VALUE_RE.matches(profile.baseUrl) || !VALUE_RE.matches(profile.command) -> "values must not contain quotes"
            else -> null
        }
    }

    /** `--model id:window` rows, else the profile's own, else what the operator types in (TTY only). */
    private fun models(args: AddArgs, profile: AddProfile): List<AddModel> {
        val given = args.models.map { spec ->
            val id = spec.substringBefore(':')
            AddModel(id, id, spec.substringAfter(':', "").toLongOrNull() ?: DEFAULT_WINDOW)
        }
        if (given.isNotEmpty() || profile.models.isNotEmpty()) return given.ifEmpty { profile.models }
        val typed = mutableListOf<AddModel>()
        while (typed.size < MAX_PROMPTED_MODELS) {
            val id = prompt("model id (blank when done):", "")
            if (id.isEmpty()) break
            val window = prompt("context window for $id:", DEFAULT_WINDOW.toString()).toLongOrNull()
            typed += AddModel(id, id, window ?: DEFAULT_WINDOW)
        }
        return typed
    }

    private fun nextPort(current: Topology): Int {
        val taken = current.heads.values.map { it.port } + listOfNotNull(current.daemon.controlPort)
        return generateSequence(maxOf(taken.max(), FIRST_HEAD_PORT - 1) + 1) { it + 1 }.first { it !in taken }
    }
}

/** A refusal decided before the candidate was parsed; its message is the whole explanation. */
private class AddRefused(message: String) : RuntimeException(message)
