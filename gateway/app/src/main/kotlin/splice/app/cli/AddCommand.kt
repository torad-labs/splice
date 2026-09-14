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
        val parsed = AddArgParser().parse(args) ?: return AddArgParser().usage()
        val candidate = prepare.candidate(parsed, env) ?: return false
        val title = "${BOLD}splice add ${candidate.args.profile}$RESET"
        println("$title $DIM— '${candidate.key}' as $CYAN${candidate.command}$RESET")
        val ok = authenticate(candidate, env) && verified(candidate, env) && save(candidate)
        if (!ok) println("${YELLOW}nothing written$RESET — ${candidate.path} is unchanged")
        return ok && finish(candidate, env)
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

    /** The only write: a sibling temp file, then ONE rename — the previous file is intact until then.
     *  The candidate was built from [AddCandidate.existing]; a sign-in and the checks ran since, so the
     *  file is read again first and a change in between (an editor, a second `splice add`) refuses the
     *  write instead of being overwritten by a rename. */
    private fun save(c: AddCandidate): Boolean {
        // Normalized the way the candidate's `existing` was (one trailing newline), or a config saved
        // without one would be "changed" on every run and never written (review 2026-09-14).
        val current = Cancellables.runCatchingCancellable { Files.readString(c.path) }.getOrNull()
            ?.let { it.trimEnd('\n') + "\n" }
        if (current != null && current != c.existing) {
            println("  $RED✗$RESET ${"saved".padEnd(ADD_PAD)} ${c.path} changed while this add was running — rerun")
            return false
        }
        val tmp = c.path.resolveSibling(c.path.fileName.toString() + ".add-${ProcessHandle.current().pid()}.tmp")
        Files.writeString(tmp, c.existing + c.appended)
        Files.move(tmp, c.path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        println("  $GREEN✓$RESET ${"saved".padEnd(ADD_PAD)} ${c.path} (+[providers.${c.key}], +[heads.${c.key}])")
        return true
    }

    /** True when the head is reachable as printed: not running (comes up on first launch), restarted,
     *  or deliberately left for `splice restart`. A restart that was asked for and failed is false, and
     *  the footer says what to run instead of a Launch line that would not work yet. */
    private fun finish(c: AddCandidate, env: EnvReader): Boolean {
        val linked = Cancellables.runCatchingCancellable { install(c.key, env) }.getOrDefault(false)
        if (!linked) {
            val fix = "${CYAN}splice install ${c.key}$RESET"
            println("  $YELLOW!$RESET ${"wrapper".padEnd(ADD_PAD)} not linked — run: $fix")
        }
        val port = AdminSupport.controlPort(c.topology, env)
        val daemonLabel = "daemon".padEnd(ADD_PAD)
        val activated = when {
            !daemonUp(port) -> true.also { println("  $daemonLabel not running — '${c.key}' comes up on first launch") }
            c.args.yes || confirm("Restart the daemon so '${c.key}' comes up now?", default = true) -> restart()
            else -> true.also { println("  $daemonLabel restart later with: ${CYAN}splice restart$RESET") }
        }
        println()
        if (activated) {
            println("  Launch      $CYAN${c.command}$RESET")
        } else {
            val then = "run ${CYAN}splice restart$RESET, then $CYAN${c.command}$RESET"
            println("  $RED✗$RESET $daemonLabel restart failed — the head is saved; $then")
        }
        println("  Checkup     ${CYAN}splice doctor$RESET $DIM— anything wrong prints its fix$RESET")
        return activated
    }

    private fun confirm(question: String, default: Boolean): Boolean =
        prompt("$question ${if (default) "[Y/n]" else "[y/N]"}", if (default) "y" else "n").lowercase().startsWith("y")
}
