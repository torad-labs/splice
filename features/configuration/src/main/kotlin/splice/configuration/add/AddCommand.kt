// NEW: v0.4.0 FEATURES.md §1 — `splice add <profile>` — a second provider without editing TOML.
// pick profile -> authenticate (the login verb's own flows) -> models -> local checks ALWAYS
// (candidate TOML parses, credential present, base URL answers, models listed where the dialect
// has a list) -> ONE optional short live turn, skipped by default -> atomic save (a temp file next
// to splice.toml, then one rename) -> restart handling -> the doctor + launch line. Anything that
// stops the flow before the save leaves the previous file byte-identical. In features/configuration
// since LAYOUT-01: every line goes to [output], and app hands in the login flow, the wrapper linker,
// the daemon probe and the restart (AddWiring).
package splice.configuration.add

import splice.core.terminal.BOLD
import splice.core.terminal.CYAN
import splice.core.terminal.DIM
import splice.core.terminal.GREEN
import splice.core.terminal.RED
import splice.core.terminal.RESET
import splice.core.terminal.TerminalOutput
import splice.core.terminal.YELLOW
import splice.core.topology.AuthKind
import splice.core.util.Cancellables
import splice.core.util.EnvReader
import splice.core.util.SafeFailureText
import splice.daemonclient.DaemonSettings
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal const val ADD_PAD = 11

/** [output] is every line the verb prints; [errors] takes only what resolving the control port says
 *  about a broken splice.toml. */
internal class AddCommand(
    private val output: TerminalOutput,
    errors: TerminalOutput,
    private val checks: AddChecks,
    private val ports: AddPorts,
) {
    private val prepare = AddPrepare(output, checks, ports.prompt)
    private val settings = DaemonSettings(errors)

    suspend fun add(args: List<String>, env: EnvReader): Boolean {
        val parsed = AddArgParser().parse(args) ?: return AddArgParser().usage(output)
        val candidate = prepare.candidate(parsed, env) ?: return false
        val title = "${BOLD}splice add ${candidate.args.profile}$RESET"
        output.line("$title $DIM— '${candidate.key}' as $CYAN${candidate.command}$RESET")
        val ok = authenticate(candidate, env) && verified(candidate, env) && save(candidate)
        if (!ok) output.line("${YELLOW}nothing written$RESET — ${candidate.path} is unchanged")
        return ok && finish(candidate, env)
    }

    private suspend fun authenticate(c: AddCandidate, env: EnvReader): Boolean = when {
        c.provider.auth.kind == AuthKind.Client.wire ->
            true.also { output.line("  ${"auth".padEnd(ADD_PAD)} your own Claude login is forwarded at launch") }
        checks.credential(c.key, c.provider, env).ok ->
            true.also { output.line("  ${"auth".padEnd(ADD_PAD)} credential already present") }
        c.args.yes || confirm("Sign in to '${c.key}' now?", default = true) ->
            ports.login(c.key, c.provider, c.topology)
        else -> false.also { output.line("  ${"auth".padEnd(ADD_PAD)} sign-in is required to add '${c.key}'") }
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
            output.line("  $glyph ${r.name.padEnd(ADD_PAD)} ${r.detail}")
        }
        return results.all { it.ok }
    }

    /** The only write: a sibling temp file, then ONE rename — the previous file is intact until then.
     *  The candidate was built from [AddCandidate.existing]; a sign-in and the checks ran since, so the
     *  file is read again first and a change in between (an editor, a second `splice add`) refuses the
     *  write instead of being overwritten by a rename. A file that cannot be read again (deleted,
     *  replaced by something unreadable) is refused the same way: the candidate was built from a file
     *  that existed, so a rename that recreated it would write stale content (review 2026-09-14). */
    private fun save(c: AddCandidate): Boolean {
        // Normalized the way the candidate's `existing` was (one trailing newline), or a config saved
        // without one would be "changed" on every run and never written (review 2026-09-14).
        val stale = Cancellables.runCatchingCancellable { Files.readString(c.path).trimEnd('\n') + "\n" }.fold(
            onSuccess = { if (it == c.existing) null else "changed while this add was running — rerun" },
            onFailure = { "could not be read again (${SafeFailureText.render(it)}) — nothing written" },
        )
        if (stale != null) {
            output.line("  $RED✗$RESET ${"saved".padEnd(ADD_PAD)} ${c.path} $stale")
            return false
        }
        val tmp = c.path.resolveSibling(c.path.fileName.toString() + ".add-${ProcessHandle.current().pid()}.tmp")
        Files.writeString(tmp, c.existing + c.appended)
        Files.move(tmp, c.path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        output.line("  $GREEN✓$RESET ${"saved".padEnd(ADD_PAD)} ${c.path} (+[providers.${c.key}], +[heads.${c.key}])")
        return true
    }

    /** True when the head is reachable as printed: not running (comes up on first launch), restarted,
     *  or deliberately left for `splice restart`. A restart that was asked for and failed is false, and
     *  the footer says what to run instead of a Launch line that would not work yet. */
    private fun finish(c: AddCandidate, env: EnvReader): Boolean {
        linkWrapper(c, env)
        val port = settings.controlPort(c.topology, env)
        val daemonLabel = "daemon".padEnd(ADD_PAD)
        val activated = when {
            !ports.daemonUp(port) -> true.also {
                output.line("  $daemonLabel not running — '${c.key}' comes up on first launch")
            }
            c.args.yes || confirm("Restart the daemon so '${c.key}' comes up now?", default = true) -> ports.restart()
            else -> true.also { output.line("  $daemonLabel restart later with: ${CYAN}splice restart$RESET") }
        }
        output.line("")
        if (activated) {
            output.line("  Launch      $CYAN${c.command}$RESET")
        } else {
            val then = "run ${CYAN}splice restart$RESET, then $CYAN${c.command}$RESET"
            output.line("  $RED✗$RESET $daemonLabel restart failed — the head is saved; $then")
        }
        output.line("  Checkup     ${CYAN}splice doctor$RESET $DIM— anything wrong prints its fix$RESET")
        return activated
    }

    private fun linkWrapper(c: AddCandidate, env: EnvReader) {
        val link = Cancellables.runCatchingCancellable { ports.install(c.key, env) }
        val linked = link.getOrElse { false }
        if (!linked) {
            val fix = "${CYAN}splice install ${c.key}$RESET"
            val why = link.exceptionOrNull()?.let { " (${SafeFailureText.render(it)})" }.orEmpty()
            output.line("  $YELLOW!$RESET ${"wrapper".padEnd(ADD_PAD)} not linked$why — run: $fix")
        }
    }

    private fun confirm(question: String, default: Boolean): Boolean {
        val answer = ports.prompt("$question ${if (default) "[Y/n]" else "[y/N]"}", if (default) "y" else "n")
        return answer.lowercase().startsWith("y")
    }
}
