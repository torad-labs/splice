// NEW: multi-harness peer auto-registration (docs/peers.md). `splice peer attach`: harness-agnostic owner-bound peer
// registration and env/hook helper so standalone CLI harnesses (`agy`, `codex`, or custom wrappers/hooks) attach once
// with automatic owner-exit retirement and optional `eval`-able env exports (`--env`).
package splice.app.cli.peer

import splice.app.cli.hook.HarnessHook
import splice.app.cli.hook.HookLook
import splice.app.cli.hook.SilentTerminal
import splice.core.terminal.TerminalOutput
import splice.core.topology.PEER_NAME_PATTERN
import splice.core.util.EnvReader
import splice.core.util.JsonScalars
import splice.sessions.peer.codex.NativeProcess
import splice.sessions.peer.owner.OwnedHarness
import splice.sessions.peer.owner.PeerOwnerProcesses
import java.nio.file.Path

private const val ENV_SWITCH = "--env"
private const val HARNESS_FLAG = "--harness"
private const val ATTACH_NAME_FLAG = "--name"
private const val DEFAULT_ATTACH_PREFIX = "peer"

// why: peer names follow PEER_NAME_PATTERN (1 letter + up to 31 alnum/dash = 32 chars total).
private const val MAX_ATTACH_NAME_LEN = 32

// why: 8 lowercase hex/alnum characters match the peer target prefix length and avoid collisions per session.
private const val SESSION_SUFFIX_CHARS = 8

/** `splice peer attach [<name>] [--name NAME] [--cwd DIR] [--send-to A,B] [--owner PID] [--harness NAME]
 *  [--env] [--json] [--hook]`. */
internal class PeerAttachVerb(
    private val call: PeerCall,
    private val out: TerminalOutput,
    private val err: TerminalOutput,
    private val env: EnvReader,
    private val hooks: HarnessHook,
    private val processes: PeerOwnerProcesses = PeerOwnerProcesses(),
) {
    private val parser = PeerArgsParser(
        valued = setOf(ATTACH_NAME_FLAG, "--cwd", "--send-to", "--owner", HARNESS_FLAG),
        standalone = setOf(ENV_SWITCH, JSON_SWITCH, HOOK_FLAG),
    )

    fun run(args: List<String>): Int? {
        val parsed = parser.parse(args)
        val look = hooks.look(parsed?.forcesHook() == true || HOOK_FLAG in args)
        return if (parsed == null || hasConflictingNames(parsed)) look.refuseUsage() else attach(parsed, look)
    }

    private fun hasConflictingNames(parsed: PeerArgs): Boolean =
        parsed.words.size > 1 || (parsed.words.isNotEmpty() && ATTACH_NAME_FLAG in parsed.values)

    private fun attach(parsed: PeerArgs, look: HookLook): Int = when (val plan = planAttach(parsed, look)) {
        is AttachPlan.Refused -> refuse(plan.reason, look)
        is AttachPlan.Ready -> submit(parsed, look, plan)
    }

    private fun planAttach(parsed: PeerArgs, look: HookLook): AttachPlan {
        val harness = parsed.values[HARNESS_FLAG]?.takeIf(String::isNotBlank)
        if (harness != null && processes.isPlainShell(harness)) {
            return AttachPlan.Refused(
                "--harness cannot be a plain shell ($harness); pass a harness binary or --owner <pid>",
            )
        }
        val extra = PeerRegistrationBody.extraPrograms(env, processes) + listOfNotNull(harness)
        val resolved = resolveOwner(parsed.values["--owner"], extra)
        val explicitName = explicitName(parsed)
        return when {
            resolved == null -> AttachPlan.Refused("--owner ${parsed.values["--owner"]} is not a running process")
            resolved.process == null && explicitName == null ->
                AttachPlan.Refused("no recognized harness process found; pass --owner <pid> or --name <name>")
            else -> AttachPlan.Ready(
                name = deriveName(explicitName, harness, resolved, look),
                cwd = resolveCwd(parsed, look),
                owner = resolved.process,
            )
        }
    }

    private fun explicitName(parsed: PeerArgs): String? =
        parsed.words.firstOrNull() ?: parsed.values[ATTACH_NAME_FLAG]?.takeIf(String::isNotBlank)

    private fun resolveCwd(parsed: PeerArgs, look: HookLook): String =
        parsed.values["--cwd"] ?: look.directory ?: Path.of("").toAbsolutePath().normalize().toString()

    private fun submit(parsed: PeerArgs, look: HookLook, plan: AttachPlan.Ready): Int {
        val body = PeerRegistrationBody.buildOwned(plan.name, parsed.values + ("--cwd" to plan.cwd), plan.owner)
        val key = call.managementKey() ?: return look.keep(PeerExit.REFUSED)
        return when (val answer = call.call("POST", "/api/peers", key, body)) {
            is PeerAnswer.Exit -> look.keep(answer.code)
            is PeerAnswer.Ok -> {
                report(look, parsed, answer, plan.owner)
                look.keep(PeerExit.DONE)
            }
        }
    }

    private fun resolveOwner(explicitPid: String?, extra: Set<String>): ResolvedOwner? {
        if (explicitPid != null) {
            val captured = explicitPid.toLongOrNull()?.let(processes::capture) ?: return null
            return ResolvedOwner(program = null, process = captured)
        }
        val ancestor: OwnedHarness? = processes.harnessAncestor(extra)
        return ResolvedOwner(program = ancestor?.program, process = ancestor?.process)
    }

    private fun refuse(message: String, look: HookLook): Int {
        err.line(message)
        return if (look.active) PeerExit.REFUSED else PeerExit.BAD_USAGE
    }

    private fun deriveName(
        explicit: String?,
        harness: String?,
        owner: ResolvedOwner,
        look: HookLook,
    ): String {
        val given = explicit ?: env(PEER_NAME_ENV)?.takeIf(String::isNotBlank)
        if (given != null) return given
        val prefix = PeerAttachNames.sanitize(harness ?: owner.program ?: DEFAULT_ATTACH_PREFIX)
            .ifEmpty { DEFAULT_ATTACH_PREFIX }
            .take(MAX_ATTACH_NAME_LEN - SESSION_SUFFIX_CHARS - 2)
        val sessionSeed = look.sessionId?.let(PeerAttachNames::sanitize)?.take(SESSION_SUFFIX_CHARS)
            ?.takeIf(String::isNotEmpty)
        val fallbackPid = ProcessHandle.current().parent().map { it.pid() }.orElse(ProcessHandle.current().pid())
        val pidSeed = (owner.process?.pid ?: fallbackPid).toString()
        return PeerAttachNames.build(prefix, sessionSeed ?: pidSeed)
    }

    private fun report(look: HookLook, parsed: PeerArgs, answer: PeerAnswer.Ok, owner: NativeProcess?) {
        val sink = if (look.active) SilentTerminal else out
        val reply = answer.body
        when {
            ENV_SWITCH in parsed.switches -> {
                val name = JsonScalars.str(reply, "name").orEmpty()
                val keyFile = JsonScalars.str(reply, "keyFile").orEmpty()
                sink.line("export $PEER_NAME_ENV=${shellQuote(name)}")
                sink.line("export $PEER_KEY_FILE_ENV=${shellQuote(keyFile)}")
            }
            JSON_SWITCH in parsed.switches -> sink.line(reply.toString())
            else -> {
                sink.line("attached ${JsonScalars.str(reply, "name")}")
                sink.line("  session id  ${JsonScalars.str(reply, "sessionId")}")
                sink.line("  key file    ${JsonScalars.str(reply, "keyFile")}")
                sink.line("  socket      ${JsonScalars.str(reply, "socket") ?: "not yet: the peer is still starting"}")
                sink.line("  owner       ${owner?.let { "pid ${it.pid}" } ?: "none"}")
            }
        }
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}

private data class ResolvedOwner(val program: String?, val process: NativeProcess?)

private sealed class AttachPlan {
    data class Refused(val reason: String) : AttachPlan()

    data class Ready(val name: String, val cwd: String, val owner: NativeProcess?) : AttachPlan()
}

/** Builds a valid peer name matching [PEER_NAME_PATTERN] from [prefix] and [suffix]. */
internal object PeerAttachNames {
    private val pattern = Regex(PEER_NAME_PATTERN)
    private val nonAllowed = Regex("[^a-z0-9-]+")
    private val multiDash = Regex("-+")

    fun build(prefix: String, suffix: String): String {
        val cleanPrefix = sanitize(prefix).trim('-').trimStart { !it.isLetter() }.ifEmpty { DEFAULT_ATTACH_PREFIX }
        val cleanSuffix = sanitize(suffix).trim('-')
        val combined = if (cleanSuffix.isEmpty()) cleanPrefix else "$cleanPrefix-$cleanSuffix"
        val trimmed = combined.take(MAX_ATTACH_NAME_LEN).trimEnd('-')
        return if (pattern.matches(trimmed)) trimmed else "$DEFAULT_ATTACH_PREFIX-${ProcessHandle.current().pid()}"
    }

    fun sanitize(raw: String): String =
        raw.lowercase().replace(nonAllowed, "-").replace(multiDash, "-").trim('-')
}
