// NEW (v0.4.0, FEATURES.md §1): `splice add <profile>` — a second provider without editing TOML.
// pick profile -> authenticate (the login verb's own flows) -> models -> local checks ALWAYS
// (candidate TOML parses, credential present, base URL answers, models listed where the dialect
// has a list) -> ONE optional short live turn, skipped by default -> atomic save (a temp file next
// to splice.toml, then one rename) -> restart handling -> the doctor + launch line. Anything that
// stops the flow before the save leaves the previous file byte-identical. :app: println-exempt.
package splice.app.cli

import splice.app.TopologyLoader
import splice.core.topology.AuthKind
import splice.core.topology.ProviderConfig
import splice.core.topology.Topology
import splice.core.util.Cancellables
import splice.core.util.EnvReader
import splice.core.util.SafeFailureText
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

private const val FIRST_HEAD_PORT = 3099
private const val DEFAULT_WINDOW = 128_000L
private const val MAX_PROMPTED_MODELS = 8
private val KEY_RE = Regex("[a-z0-9][a-z0-9-]*")
private val VALUE_RE = Regex("[^\"\\\\\\p{Cntrl}]+")
private const val PAD = 11
private val VALUED_FLAGS: Map<String, (AddArgs, String) -> AddArgs> = mapOf(
    "--name" to { p, v -> p.copy(name = v) },
    "--base-url" to { p, v -> p.copy(baseUrl = v) },
    "--model" to { p, v -> p.copy(models = p.models + v) },
    "--command" to { p, v -> p.copy(command = v) },
)

/** Answers a question with the operator's line, or [default] when there is no terminal. */
internal fun interface AddPrompter {
    operator fun invoke(question: String, default: String): String
}

internal class ConsolePrompter : AddPrompter {
    override fun invoke(question: String, default: String): String {
        if (System.console() == null) return default
        print("$question ${if (default.isEmpty()) "" else "[$default] "}")
        val line = Cancellables.runCatchingCancellable { readlnOrNull()?.trim() }.getOrNull()
        return line?.ifEmpty { default } ?: default
    }
}

/** The sign-in for a head that is not in the topology yet; production runs the login verb's flow. */
internal fun interface AddLogin {
    suspend operator fun invoke(key: String, provider: ProviderConfig, topology: Topology): Boolean
}

internal data class AddArgs(
    val profile: String? = null,
    val name: String? = null,
    val baseUrl: String? = null,
    val models: List<String> = emptyList(),
    val command: String? = null,
    val live: Boolean = false,
    val yes: Boolean = false,
)

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

internal class AddCommand(
    private val checks: AddChecks = AddChecks(),
    private val login: AddLogin = AddLogin { key, provider, topology ->
        LoginCommand().runLoginFlow(key, provider, topology)
    },
    private val install: (String, EnvReader) -> Boolean = { key, env -> InstallCommand().install(key, env) },
    private val restart: () -> Boolean = { RestartCommand().restart() },
    private val daemonUp: (Int) -> Boolean = { port -> AdminSupport.daemonUp(port) },
    private val prompt: AddPrompter = ConsolePrompter(),
) {
    private val profiles = AddProfiles()

    suspend fun add(args: List<String>, env: EnvReader = EnvReader(System::getenv)): Boolean {
        val candidate = prepare(parse(args), env) ?: return false
        val title = "${BOLD}splice add ${candidate.args.profile}$RESET"
        println("$title $DIM— '${candidate.key}' as $CYAN${candidate.command}$RESET")
        val ok = authenticate(candidate, env) && verified(candidate, env)
        if (ok) {
            save(candidate)
            finish(candidate, env)
        } else {
            println("${YELLOW}nothing written$RESET — ${candidate.path} is unchanged")
        }
        return ok
    }

    private fun parse(args: List<String>): AddArgs {
        var parsed = AddArgs()
        var i = 0
        while (i < args.size) {
            val a = args[i]
            val v = args.getOrNull(i + 1).orEmpty()
            val valued = VALUED_FLAGS[a]
            parsed = when {
                valued != null -> valued(parsed, v).also { i++ }
                a == "--live" -> parsed.copy(live = true)
                a == "--yes" || a == "-y" -> parsed.copy(yes = true)
                else -> parsed.copy(profile = parsed.profile ?: a)
            }
            i++
        }
        return parsed
    }

    private fun prepare(args: AddArgs, env: EnvReader): AddCandidate? {
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

    private suspend fun authenticate(c: AddCandidate, env: EnvReader): Boolean = when {
        c.provider.auth.kind == AuthKind.Client.wire ->
            true.also { println("  ${"auth".padEnd(PAD)} your own Claude login is forwarded at launch") }
        checks.credential(c.key, c.provider, env).ok ->
            true.also { println("  ${"auth".padEnd(PAD)} credential already present") }
        c.args.yes || confirm("Sign in to '${c.key}' now?", default = true) -> login(c.key, c.provider, c.topology)
        else -> false.also { println("  ${"auth".padEnd(PAD)} sign-in is required to add '${c.key}'") }
    }

    private fun verified(c: AddCandidate, env: EnvReader): Boolean {
        val asked = !c.args.yes && confirm("Run one short live turn against '${c.key}' now?", default = false)
        val live = c.args.live || asked
        val results = listOf(
            checks.credential(c.key, c.provider, env),
            checks.reachable(c.provider.baseUrl),
            checks.modelsListed(c.models, checks.listedModels(c.provider, c.key, env)),
        ) + listOfNotNull(if (live) checks.liveTurn(c.provider, c.key, c.models.first(), env) else null)
        results.forEach { r ->
            val glyph = if (r.ok) "$GREEN✓$RESET" else "$RED✗$RESET"
            println("  $glyph ${r.name.padEnd(PAD)} ${r.detail}")
        }
        return results.all { it.ok }
    }

    /** The only write: a sibling temp file, then ONE rename — the previous file is intact until then. */
    private fun save(c: AddCandidate) {
        val tmp = c.path.resolveSibling(c.path.fileName.toString() + ".add-${ProcessHandle.current().pid()}.tmp")
        Files.writeString(tmp, c.existing + c.appended)
        Files.move(tmp, c.path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        println("  $GREEN✓$RESET ${"saved".padEnd(PAD)} ${c.path} (+[providers.${c.key}], +[heads.${c.key}])")
    }

    private fun finish(c: AddCandidate, env: EnvReader) {
        val linked = Cancellables.runCatchingCancellable { install(c.key, env) }.getOrDefault(false)
        if (!linked) {
            val fix = "${CYAN}splice install ${c.key}$RESET"
            println("  $YELLOW!$RESET ${"wrapper".padEnd(PAD)} not linked — run: $fix")
        }
        val port = AdminSupport.controlPort(c.topology, env)
        when {
            !daemonUp(port) -> println("  ${"daemon".padEnd(PAD)} not running — '${c.key}' comes up on first launch")
            c.args.yes || confirm("Restart the daemon so '${c.key}' comes up now?", default = true) -> restart()
            else -> println("  ${"daemon".padEnd(PAD)} restart later with: ${CYAN}splice restart$RESET")
        }
        println()
        println("  Launch      $CYAN${c.command}$RESET")
        println("  Checkup     ${CYAN}splice doctor$RESET $DIM— anything wrong prints its fix$RESET")
    }

    private fun confirm(question: String, default: Boolean): Boolean =
        prompt("$question ${if (default) "[Y/n]" else "[y/N]"}", if (default) "y" else "n").lowercase().startsWith("y")
}

/** A refusal decided before the candidate was parsed; its message is the whole explanation. */
private class AddRefused(message: String) : RuntimeException(message)
