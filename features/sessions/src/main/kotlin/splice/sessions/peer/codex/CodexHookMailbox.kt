// NEW: Oct 28, 2026 (docs/peers.md, "A Codex thread no app-server holds"): where a codex peer leaves its messages for
// a thread no app-server holds, such as one `codex resume --no-daemon` serves in its own process. The helper leaves each
// message, in Claude Code's envelope, as one file under `<peer state dir>/codex-hook/`, and the thread's next hook
// (`splice peer codex hook`) takes them in the order they were left. A take claims each file by renaming it before it
// reads it, so two hooks that run at once never hand one message over twice. The folder sits in the peer's state
// folder, which the helper keeps owner-only, and each file is 0600.
package splice.sessions.peer.codex

import splice.core.util.SafeFailureText
import splice.core.util.SecureFile
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicLong

/** The turn a message left for the thread's next hook is journaled under. */
internal const val HOOK_TURN: String = "next-hook"

private const val HOOK_FOLDER = "codex-hook"
private const val LEFT = ".msg"
private const val TAKEN = ".taken-"

// why: a name is the time it was left and a count within this process, both zero-padded, so names sort in the order
// the messages were left: 13 digits hold epoch milliseconds past the year 2286, and 9 digits a billion messages.
private const val NAME_FORMAT = "%013d-%09d"

/** A thread's waiting messages, in `<peer state dir>/codex-hook/`. */
public class CodexHookMailbox(peerDir: Path) {
    private val dir = peerDir.resolve(HOOK_FOLDER)
    private val count = AtomicLong()

    /** Leaves [text] for the thread's next hook. */
    public fun leave(text: String) {
        val name = NAME_FORMAT.format(System.currentTimeMillis(), count.incrementAndGet()) + LEFT
        SecureFile.writeAtomic0600(dir.resolve(name), text)
    }

    /** Takes every message left so far, in the order they were left; each one is taken by one caller only. */
    public fun take(): List<String> = left().mapNotNull(::claim)

    private fun left(): List<Path> = try {
        Files.list(dir).use { files ->
            files.filter { it.fileName.toString().endsWith(LEFT) }.toList().sortedBy { it.fileName.toString() }
        }
    } catch (_: NoSuchFileException) {
        emptyList()
    }

    /** The text of [file] once this caller holds it, or null when another caller took it first. */
    private fun claim(file: Path): String? {
        val held = file.resolveSibling(file.fileName.toString() + TAKEN + ProcessHandle.current().pid())
        try {
            Files.move(file, held, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: NoSuchFileException) {
            return null
        }
        val text = Files.readString(held)
        Files.deleteIfExists(held)
        return text
    }
}

/** A thread no app-server holds, as a codex peer's delivery reaches it: through [mailbox], at the thread's next hook.
 *  A message left there is `started` in the journal under the turn [HOOK_TURN]. */
public class CodexHookThread(private val mailbox: CodexHookMailbox) : CodexThread {
    override suspend fun startTurn(clientId: String, text: String): TurnStart = try {
        mailbox.leave(text)
        TurnStart.Started(HOOK_TURN)
    } catch (failure: IOException) {
        val why = SafeFailureText.render(failure)
        TurnStart.Refused("the message could not be left for the thread's next hook: $why")
    }

    /** A message left for a hook is never handed over twice, so an interrupted one is not looked for. */
    override suspend fun holds(clientId: String): Boolean? = null
}
