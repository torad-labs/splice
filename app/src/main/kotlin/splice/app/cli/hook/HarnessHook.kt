// NEW: Oct 27, 2026. A splice command whose own process is a Claude Code or Codex hook goes through
// here. Both harnesses write one JSON object to that process's stdin, and both treat exit 2 as
// "block this turn": Codex 0.159 (codex-rs/hooks/src/events/user_prompt_submit.rs, the `Some(2)` arm)
// stops the turn when a UserPromptSubmit command exits 2 with any stderr, and Claude Code does the
// same for UserPromptSubmit. Exit 0 with text on stdout is worse in the other direction: Codex adds
// plain stdout to the model's context and reads a JSON object there as a decision (`continue`,
// `decision`, `hookSpecificOutput`). The payload itself is the same three fields on SessionStart,
// UserPromptSubmit and SessionEnd (codex-rs/hooks/src/schema.rs, rust-v0.159.0): `session_id`, `cwd`,
// `hook_event_name`. Codex does not put the thread in the hook's environment.
//
// The hooks splice installs for Claude Code are bash, and each of those scripts already exits 0.
// The login and key-capture scripts do block a prompt, on purpose, by printing a decision: that is
// how a pasted key is kept from the model. They are not this path. A command that becomes the hook
// process calls [HarnessHook] so a missing id cannot take the usage exit.
package splice.app.cli.hook

import kotlinx.serialization.json.Json
import splice.app.cli.peer.PeerExit.DONE
import splice.core.terminal.TerminalOutput
import splice.core.util.JsonScalars
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/** Exit 2: the one status Claude Code and Codex both treat as blocking the turn. */
internal const val HARNESS_BLOCK_EXIT: Int = 2

/** Whether file descriptor 0 is a terminal. A hook's stdin is a pipe, so this is how a command tells
 *  a hook from an operator without also treating a piped stdout as one (System.console() is null then,
 *  and reading a real terminal would wait until the operator typed Ctrl-D). */
internal fun interface StdinTerminal {
    fun isTerminal(): Boolean
}

/** The complete hook payload supplied on standard input. */
internal fun interface HookStdin {
    operator fun invoke(): String
}

/** `/proc/self/fd/0` names a terminal; anything else, including a pipe, does not. */
internal object StdinKind {
    fun terminal(link: String): Boolean = link.startsWith("/dev/pts/") || link.startsWith("/dev/tty")
}

/** The production [StdinTerminal]. When the kernel will not say, a missing console is not a terminal:
 *  a hook has no console, and treating it as one would skip the payload and exit 2. */
internal class StdinDevice : StdinTerminal {
    private val stdin = Path.of("/proc/self/fd/0")

    override fun isTerminal(): Boolean {
        val link = try {
            Files.readSymbolicLink(stdin).toString()
        } catch (_: IOException) {
            null
        } catch (_: UnsupportedOperationException) {
            null
        }
        return if (link == null) System.console() != null else StdinKind.terminal(link)
    }
}

/** What a hook may print. A line here would be injected as context or read as a decision. */
internal object SilentTerminal : TerminalOutput {
    override fun line(text: String) = Unit
}

/** One look at stdin. [active] means this process is a hook: stdout stays empty and exit 2 is not
 *  returned. [piped] means stdin was not a terminal, so a missing id is a no-op even when the bytes
 *  were not a payload. [sessionId] and [cwd] come from the payload. */
internal class HookLook(
    val active: Boolean,
    private val piped: Boolean,
    val sessionId: String?,
    private val cwd: String?,
    private val err: TerminalOutput,
    /** The payload's `hook_event_name`, only once the bytes are a hook payload. */
    val event: String? = null,
) {
    /** The payload's working directory, only once the bytes are a hook payload. */
    val directory: String? get() = if (active) cwd else null

    /** Bad arguments. A hook exits 1, which neither harness treats as a block; an operator gets null
     *  so the verb can print its usage and exit 2. */
    fun refuseUsage(): Int? = if (hooked()) {
        err.line(BAD_ARGS)
        NON_BLOCKING
    } else {
        null
    }

    /** No thread id. A hook, or any non-terminal run, leaves the session alone: exit 0, the reason on
     *  stderr, nothing on stdout. An operator at a terminal gets null and the verb's usage exit. */
    fun noThread(): Int? = if (hooked()) {
        err.line(NO_THREAD)
        DONE
    } else {
        null
    }

    /** [code] as a hook may return it. Exit 2 becomes 0 so a later caller cannot block the turn. */
    fun keep(code: Int): Int = if (hooked() && code == HARNESS_BLOCK_EXIT) DONE else code

    private fun hooked(): Boolean = active || piped
}

/** Reads a harness hook's stdin once and decides whether this process may block a turn. */
internal class HarnessHook(
    private val stdin: HookStdin,
    private val terminal: StdinTerminal,
    private val err: TerminalOutput,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** [forced] is the command's `--hook` switch: the contract applies even though stdin is a terminal
     *  and there is no payload to read. A terminal's stdin is left unread. */
    fun look(forced: Boolean): HookLook {
        if (terminal.isTerminal()) return HookLook(forced, piped = false, sessionId = null, cwd = null, err)
        val payload = read(stdin())
        val active = forced || payload.event != null || payload.sessionId != null
        val session = if (active) payload.sessionId else null
        val cwd = if (active) payload.cwd else null
        return HookLook(active, piped = true, session, cwd, err, payload.event.takeIf { active })
    }

    private fun read(text: String): HookFields {
        val obj = JsonScalars.objectOrNull(json, text.trim()) ?: return HookFields(null, null, null)
        return HookFields(
            JsonScalars.str(obj, EVENT)?.takeIf(String::isNotBlank),
            JsonScalars.str(obj, HOOK_SESSION_ID_FIELD)?.takeIf(String::isNotBlank),
            JsonScalars.str(obj, CWD)?.takeIf(String::isNotBlank),
        )
    }
}

private data class HookFields(val event: String?, val sessionId: String?, val cwd: String?)

private const val EVENT: String = "hook_event_name"
private const val HOOK_SESSION_ID_FIELD: String = "session_id"
private const val CWD: String = "cwd"

// why: both harnesses reserve exit 2 for blocking; exit 1 reports bad hook arguments without blocking the turn.
private const val NON_BLOCKING: Int = 1
private const val NO_THREAD: String = "splice peer: no Codex thread id; leaving the session alone"
private const val BAD_ARGS: String = "splice peer: these arguments are not a Codex hook invocation"
