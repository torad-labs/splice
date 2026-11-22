// NEW: a mode's hooks as the harness's mode-hook port (modes/README.md, "Hooks"): the hooks of every mode on for the
// session's head and caller, in the order their manifests decide, each answered by the mode's handler in-process or by
// the worker its manifest names.
package splice.app.modes

import splice.harness.agent.AgentSession
import splice.harness.hook.HookAnswer
import splice.harness.hook.HookCall
import splice.harness.hook.HookMoment
import splice.harness.hook.HookRun
import splice.harness.hook.HookSpec
import splice.harness.hook.HookWorker
import splice.harness.hook.ModeHookPort
import splice.modes.api.FoundMode
import splice.modes.api.ModeCaller
import splice.modes.api.ModeHook
import splice.modes.api.ModeHookAnswer
import splice.modes.api.ModeHookCall
import splice.modes.api.ModeHookMoment
import splice.modes.api.ModeHookWorker
import splice.modes.api.ModeSession
import splice.modes.api.ModeHooks as ModeHooksAnswer

/** Head [head]'s mode hooks, read from [modes] on every request, so a switch reaches the next one. */
internal class ModeHooks(private val modes: DaemonModes, private val head: String) : ModeHookPort {
    override fun hooks(session: AgentSession): List<HookSpec> {
        val caller = caller(session.caller) ?: return emptyList()
        return modes.hooksOn(head, caller).flatMap { mode -> mode.hooks.map { spec(mode, it) } }
    }

    private fun spec(mode: FoundMode, hook: ModeHook): HookSpec = HookSpec(
        mode.name,
        hookMoment(hook.moment),
        mode.hookOrder,
        hook.timeoutS * MILLIS,
        hook.failClosed,
        worker(mode, hook),
    )

    private fun worker(mode: FoundMode, hook: ModeHook): HookWorker = when (val worker = hook.worker) {
        null -> HookWorker.InProcess(HookRun { call -> inProcess(mode, call) })
        is ModeHookWorker.Http -> HookWorker.Http(worker.url, worker.authEnv)
        is ModeHookWorker.Mcp -> HookWorker.Mcp(worker.server, worker.tool)
    }

    private fun inProcess(mode: FoundMode, call: HookCall): HookAnswer {
        val handler = mode.handler as? ModeHooksAnswer ?: return HookAnswer.Pass
        val answer = handler.hook(
            ModeHookCall(
                modeMoment(call.moment),
                session(call.session),
                call.body,
                modes.store(mode.name),
            ),
        )
        return answer(answer)
    }

    private fun session(session: AgentSession): ModeSession =
        ModeSession(session.id, session.folder, session.head, session.model, caller(session.caller) ?: ModeCaller.HEAD)

    private fun caller(wire: String): ModeCaller? = ModeCaller.entries.firstOrNull { it.wire == wire }

    private fun hookMoment(moment: ModeHookMoment): HookMoment = HookMoment.entries.first { it.wire == moment.wire }

    private fun modeMoment(moment: HookMoment): ModeHookMoment = ModeHookMoment.entries.first { it.wire == moment.wire }

    private fun answer(answer: ModeHookAnswer): HookAnswer = when (answer) {
        ModeHookAnswer.Pass -> HookAnswer.Pass
        is ModeHookAnswer.AddContext -> HookAnswer.AddContext(answer.text)
        is ModeHookAnswer.ChooseModel -> HookAnswer.ChooseModel(answer.model)
        is ModeHookAnswer.Refuse -> HookAnswer.Refuse(answer.reason)
        is ModeHookAnswer.Rewrite -> HookAnswer.Rewrite(answer.body)
        is ModeHookAnswer.Replace -> HookAnswer.Replace(answer.text)
        is ModeHookAnswer.Continue -> HookAnswer.Continue(answer.text)
    }
}

/** One second, in the milliseconds the harness times a hook by. */
private const val MILLIS = 1_000L
