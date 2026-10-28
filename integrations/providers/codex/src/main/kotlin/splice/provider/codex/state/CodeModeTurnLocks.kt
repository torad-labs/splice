// NEW: queued coroutine callers retain a conversation lock until its last user releases it.
package splice.provider.codex.state

import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import splice.core.client.AgentConversationKey
import splice.core.perf.InputDigest
import splice.core.util.JsonScalars
import splice.provider.codex.CODE_MODE_FIELD_ROLE
import splice.provider.codex.CODE_MODE_ROLE_USER
import splice.provider.codex.CodexCodeModeBridge
import splice.upstream.RoundBody
import java.security.MessageDigest

/** Full upstream drives share a mutex only when they are the same branch of one conversation, the way codex runs
 *  a forked thread beside its parent: two branches of a conversation never wait on each other. Each drive counts
 *  in its conversation while it is queued or running, so a step that would trim the conversation's records can
 *  tell whether a sibling branch still stands on them. */
internal class CodeModeTurnLocks {
    class Entry {
        val mutex = Mutex()
        var users = 0
    }

    private val monitor = Any()
    private val keys = mutableMapOf<String, Entry>()
    private val drives = mutableMapOf<String, Int>()

    suspend fun acquire(branch: String, conversation: String): Entry {
        val entry = synchronized(monitor) {
            drives[conversation] = (drives[conversation] ?: 0) + 1
            keys.getOrPut(branch) { Entry() }.also { it.users++ }
        }
        var acquired = false
        return try {
            entry.mutex.lock()
            acquired = true
            entry
        } finally {
            if (!acquired) unreference(branch, conversation, entry)
        }
    }

    fun release(branch: String, conversation: String, entry: Entry) {
        entry.mutex.unlock()
        unreference(branch, conversation, entry)
    }

    /** True while no other drive of [conversation] is queued or running. */
    fun alone(conversation: String): Boolean = synchronized(monitor) { (drives[conversation] ?: 0) <= 1 }

    private fun unreference(branch: String, conversation: String, entry: Entry) = synchronized(monitor) {
        entry.users--
        if (entry.users == 0) keys.remove(branch)
        val left = (drives[conversation] ?: 1) - 1
        if (left == 0) drives.remove(conversation) else drives[conversation] = left
    }
}

/** The exact conversation key and request digest that acquire the coroutine turn lock. A subagent is its own
 *  conversation, the way codex keeps one thread per conversation: two subagents of one session that open with
 *  identical words never wait on each other's turn or share a script, because [conversationKey] tells them apart. */
internal class CodeModeTurnIdentity {
    fun turnKey(turn: CodexCodeModeBridge.Turn): String = digest(
        "${turn.sessionId}${0.toChar()}${turn.conversationKey}${0.toChar()}${turn.model}",
    )

    /** Model changes can supersede parked programs, but never sibling conversations or a busy engine. */
    fun conversationId(turn: CodexCodeModeBridge.Turn): String = digest(
        "${turn.sessionId}${0.toChar()}${turn.conversationKey}",
    )

    /** The conversation key a turn of [agentId] carries: the opening's own key on the main thread, which names no
     *  agent, so the records it persisted are still found; the opening and the subagent otherwise. */
    fun conversationKey(opening: String, agentId: String?): String = AgentConversationKey.of(opening, agentId)

    /** The branch of conversation [turnKey] that [input] continues, named by the newest user message in it. A
     *  message Claude Code delivers to a running agent rides beside a tool result on one branch while another branch
     *  goes on without it, so the two name different branches. Retries and the steps of one branch carry the same
     *  newest user message, so they still take turns. */
    fun branchKey(turnKey: String, input: JsonArray?): String {
        val newest = input?.lastOrNull { item ->
            (item as? JsonObject)?.let { JsonScalars.str(it, CODE_MODE_FIELD_ROLE) } == CODE_MODE_ROLE_USER
        }
        return digest("$turnKey${0.toChar()}${newest ?: ""}")
    }

    fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }

    /** The request digest records persist, without rendering a tree to reach it. A tree streams the
     *  bytes JsonWire writes, which are its rendered text's UTF-8, so a record written from a round's
     *  text is found by the same round arriving as a tree (CodeModeRoundTreeTest). */
    fun digest(body: RoundBody): String = when (body) {
        is RoundBody.Tree -> InputDigest.hex(body.element)
        is RoundBody.Text -> digest(body.text)
    }
}
