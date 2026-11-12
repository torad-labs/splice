// NEW: Oct 26, 2026 (a relaunch brings a session back as it was) — the person's own `claude`, wrapped, as the command
// a relaunch starts a session on again. A session splice did not start in a head's own config folder was started
// through that `claude`, which is the one command that runs in his own folders, so it is signed in as he is.
//
// It sits beside [CLAUDE_HEAD_KEY] because the answer is only the wrapped `claude` for THAT head: the wrap points the
// person's `claude` at one head's gateway, and relaunching any other head's session through it would move the session
// to another head behind the person's back. The key stays internal for the reason its own comment gives, so this is
// the question asked across the module boundary instead of the key being spelled on the other side.
package splice.launch.wrap

import splice.client.wrap.WrappedHead
import java.nio.file.Path

/** The wrapped `claude` of the one wrap [wrap] reads, for the head it launches. */
public class WrappedClaudeLaunch(private val wrap: WrappedHead) {
    /** Where `splice install` links the person's `claude`, while it is wrapped and launches [head]; null while it is
     *  not wrapped, and for every other head. */
    public fun of(head: String): Path? = wrap.command()?.takeIf { head == CLAUDE_HEAD_KEY }

    /** Whether [head]'s own command runs Claude Code in the person's own folder, as his wrapped `claude` does: the
     *  Claude head does under either name, wrapped or not (Nov 12, 2026), and no other head does. */
    public fun ownHome(head: String): Boolean = head == CLAUDE_HEAD_KEY

    /** The name a person types to reach [head] when it is not the head's own command: `claude` for the Claude head
     *  while his `claude` is wrapped. Null for every other head, and while it is not wrapped. A person who uses
     *  Claude only through splice types `claude`, and a screen that names the command `claude-splice` shows him
     *  a second Claude he never starts (Marcos, Nov 12, 2026: "why ... is claude and claude-splice two identities?"). */
    public fun typed(head: String): String? =
        if (head == CLAUDE_HEAD_KEY) wrap.command()?.fileName?.toString() else null
}
