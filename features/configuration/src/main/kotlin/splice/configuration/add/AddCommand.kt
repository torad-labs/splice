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
import splice.core.util.EnvReader
import splice.daemonclient.DaemonSettings

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
        return added(candidate, env)
    }

    /** A head a local runtime described (RuntimeHeadAdd): no command line, the same everything else. */
    suspend fun addDescribed(profile: AddProfile, env: EnvReader): Boolean {
        val candidate = prepare.described(profile, env) ?: return false
        return added(candidate, env)
    }

    private suspend fun added(candidate: AddCandidate, env: EnvReader): Boolean {
        val title = "$BOLD${candidate.resolved.origin}$RESET"
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
        val results = checks.all(c, live, env)
        results.forEach { r ->
            val glyph = if (r.ok) "$GREEN✓$RESET" else "$RED✗$RESET"
            output.line("  $glyph ${r.name.padEnd(ADD_PAD)} ${r.detail}")
        }
        return results.all { it.ok }
    }

    /** The only write (AddWrite): the file re-read first, then one rename. */
    private fun save(c: AddCandidate): Boolean {
        val label = "saved".padEnd(ADD_PAD)
        return when (val written = AddWrite().write(c)) {
            is AddWritten.Refused -> false.also { output.line("  $RED✗$RESET $label ${c.path} ${written.stale}") }
            AddWritten.Written -> true.also {
                output.line("  $GREEN✓$RESET $label ${c.path} (+[providers.${c.key}], +[heads.${c.key}])")
            }
        }
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
        val link = AddWrapperLink(ports.install).link(c.key, env)
        if (link is AddLinked.NotLinked) {
            val fix = "${CYAN}splice install ${c.key}$RESET"
            val why = link.why?.let { " ($it)" }.orEmpty()
            output.line("  $YELLOW!$RESET ${"wrapper".padEnd(ADD_PAD)} not linked$why — run: $fix")
        }
    }

    private fun confirm(question: String, default: Boolean): Boolean {
        val answer = ports.prompt("$question ${if (default) "[Y/n]" else "[y/N]"}", if (default) "y" else "n")
        return answer.lowercase().startsWith("y")
    }
}
