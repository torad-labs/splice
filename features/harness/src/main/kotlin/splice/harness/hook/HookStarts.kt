// NEW: the modes' session-start hooks at a head (modes/README.md, "Hooks"): what each leaves in its thread, replayed
// byte for byte on the thread's later requests, or the reason one refused the turn.
package splice.harness.hook

import splice.harness.HOOK_CONTEXT
import splice.harness.HOOK_MODEL
import splice.harness.HookState
import splice.harness.ThreadRecords
import splice.harness.agent.AgentSession
import splice.head.port.HarnessRequest
import splice.head.port.HarnessTurn

/** The modes' session-start hooks, over the threads' [records]. */
internal class HookStarts(private val hooks: HookRuns?, private val records: ThreadRecords) {
    /** What [session]'s session-start hooks leave: on the thread's first turn they run and are recorded; on a later
     *  request the thread's record holds what they left. */
    suspend fun of(
        session: AgentSession,
        request: HarnessRequest,
        turn: HarnessTurn,
        thread: String?,
        first: Boolean,
    ): Start {
        if (thread == null) return Start()
        if (!first) return recorded(request, thread)
        return fresh(session, turn) ?: Start()
    }

    /** What the thread's earlier session-start hooks left. */
    private fun recorded(request: HarnessRequest, thread: String): Start {
        val hash = records.hashOf(request.firstUser)
        val record = records.of(thread)
        return Start(contexts = record.hookTextsFor(hash), model = record.hookModelFor(hash))
    }

    /** What the hooks leave on a thread's first turn, or null where no mode declares one. */
    private suspend fun fresh(session: AgentSession, turn: HarnessTurn): Start? {
        val runs = hooks ?: return null
        val batch = runs.run(HookMoment.SESSION_START, session, turn.sending)
        val states = batch.answers.mapNotNull { (spec, answer) ->
            when (answer) {
                is HookAnswer.AddContext -> HookState("mode:${spec.mode}", HOOK_CONTEXT, answer.text)
                is HookAnswer.ChooseModel -> HookState("mode:${spec.mode}", HOOK_MODEL, answer.model)
                HookAnswer.Pass, is HookAnswer.Refuse, is HookAnswer.Rewrite, is HookAnswer.Replace,
                is HookAnswer.Continue,
                -> null
            }
        }
        return Start(
            contexts = batch.answers.mapNotNull { (_, answer) -> (answer as? HookAnswer.AddContext)?.text },
            model = batch.answers.mapNotNull { (_, answer) -> (answer as? HookAnswer.ChooseModel)?.model }.lastOrNull(),
            states = states,
            refusal = batch.refusal,
            refusedBy = batch.refusedBy,
        )
    }

    /** What a thread's session-start hooks left: the texts to place, the model it chose, the states to record, and the
     *  reason one refused the turn. */
    data class Start(
        val contexts: List<String> = emptyList(),
        val model: String? = null,
        val states: List<HookState> = emptyList(),
        val refusal: String? = null,
        val refusedBy: String? = null,
    )
}
