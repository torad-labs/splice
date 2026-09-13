// NEW: v0.4.0 FEATURES.md §1 — `splice add <profile>` — a second provider without editing TOML.
// pick profile -> authenticate (the login verb's own flows) -> models -> local checks ALWAYS
// (candidate TOML parses, credential present, base URL answers, models listed where the dialect
// has a list) -> ONE optional short live turn, skipped by default -> atomic save (a temp file next
// to splice.toml, then one rename) -> restart handling -> the doctor + launch line. Anything that
// stops the flow before the save leaves the previous file byte-identical. :app: println-exempt.
package splice.app.cli

import splice.core.topology.AuthKind
import splice.core.util.Cancellables
import splice.core.util.EnvReader
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal const val ADD_PAD = 11
internal class AddCommand(
    private val checks: AddChecks = AddChecks(),
    private val login: AddLogin = AddLogin { key, provider, topology ->
        LoginCommand().runLoginFlow(key, provider, topology)
    },
    private val install: WrapperInstall = WrapperInstall { key, env -> InstallCommand().install(key, env) },
    private val restart: DaemonRestart = DaemonRestart { RestartCommand().restart() },
    private val daemonUp: DaemonUpProbe = DaemonUpProbe { port -> AdminSupport.daemonUp(port) },
    private val prompt: AddPrompter = ConsolePrompter(),
) {
    private val prepare = AddPrepare(checks, prompt)

    suspend fun add(args: List<String>, env: EnvReader = EnvReader(System::getenv)): Boolean {
        val candidate = prepare.candidate(AddArgParser().parse(args), env) ?: return false
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

    private suspend fun authenticate(c: AddCandidate, env: EnvReader): Boolean = when {
        c.provider.auth.kind == AuthKind.Client.wire ->
            true.also { println("  ${"auth".padEnd(ADD_PAD)} your own Claude login is forwarded at launch") }
        checks.credential(c.key, c.provider, env).ok ->
            true.also { println("  ${"auth".padEnd(ADD_PAD)} credential already present") }
        c.args.yes || confirm("Sign in to '${c.key}' now?", default = true) -> login(c.key, c.provider, c.topology)
        else -> false.also { println("  ${"auth".padEnd(ADD_PAD)} sign-in is required to add '${c.key}'") }
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
            println("  $glyph ${r.name.padEnd(ADD_PAD)} ${r.detail}")
        }
        return results.all { it.ok }
    }

    /** The only write: a sibling temp file, then ONE rename — the previous file is intact until then. */
    private fun save(c: AddCandidate) {
        val tmp = c.path.resolveSibling(c.path.fileName.toString() + ".add-${ProcessHandle.current().pid()}.tmp")
        Files.writeString(tmp, c.existing + c.appended)
        Files.move(tmp, c.path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        println("  $GREEN✓$RESET ${"saved".padEnd(ADD_PAD)} ${c.path} (+[providers.${c.key}], +[heads.${c.key}])")
    }

    private fun finish(c: AddCandidate, env: EnvReader) {
        val linked = Cancellables.runCatchingCancellable { install(c.key, env) }.getOrDefault(false)
        if (!linked) {
            val fix = "${CYAN}splice install ${c.key}$RESET"
            println("  $YELLOW!$RESET ${"wrapper".padEnd(ADD_PAD)} not linked — run: $fix")
        }
        val port = AdminSupport.controlPort(c.topology, env)
        val daemonLabel = "daemon".padEnd(ADD_PAD)
        when {
            !daemonUp(port) -> println("  $daemonLabel not running — '${c.key}' comes up on first launch")
            c.args.yes || confirm("Restart the daemon so '${c.key}' comes up now?", default = true) -> restart()
            else -> println("  $daemonLabel restart later with: ${CYAN}splice restart$RESET")
        }
        println()
        println("  Launch      $CYAN${c.command}$RESET")
        println("  Checkup     ${CYAN}splice doctor$RESET $DIM— anything wrong prints its fix$RESET")
    }

    private fun confirm(question: String, default: Boolean): Boolean =
        prompt("$question ${if (default) "[Y/n]" else "[y/N]"}", if (default) "y" else "n").lowercase().startsWith("y")
}
