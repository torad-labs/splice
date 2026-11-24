// NEW: desktop-antigravity (docs/specs/desktop-antigravity.md, decision 5). One Code tab session in the pipe: the
// program it runs on now, and the session's `initialize`, kept from its first line. A `set_model` that crosses between
// Antigravity and any other model ends the program and starts the other one on the same session id (Claude Code with
// `--resume`, or `splice desktop acp` standing in for it), replays the kept `initialize`, then the `set_model`, which
// the new program answers. Going to Antigravity, Claude Code is only told its input ended, so it finishes its turn;
// going back, the translator is given the `set_model`, which ends it once its turn is cancelled. Every other line goes
// to the program as it came.
package splice.app.desktop.pipe

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import splice.core.terminal.TerminalOutput
import splice.core.util.SafeFailureText
import splice.provider.antigravity.ANTIGRAVITY_MODEL_PREFIX
import java.io.IOException

/** A session whose programs [legs] start, each waited on on [io] in [scope] and given [graceMs] to end for a switch;
 *  what the pipe [heard] drives it, and what goes wrong goes to [err]. */
internal class PipeSession(
    private val legs: PipeLegs,
    private val io: CoroutineDispatcher,
    private val graceMs: Long,
    private val err: TerminalOutput,
    private val heard: Channel<Heard>,
    private val scope: CoroutineScope,
) {
    private val asks = DesktopAskReader()
    private var initialize: String? = null

    @Volatile
    private var running: Running? = null

    /** Starts the program for [first]; whether it started. */
    fun open(first: ClaudeLine): Boolean = start(first) != null

    /** Serves the session until its program ends; that program's exit code, or null when a switch could not start
     *  the next one. */
    suspend fun serve(): Int? {
        while (true) {
            val now = checkNotNull(running)
            when (val next = heard.receive()) {
                is Heard.Line -> if (!relay(now, next.text)) return null
                Heard.InputEnded -> now.leg.endInput()
                is Heard.Ended -> if (next.leg === now.leg) return next.code
            }
        }
    }

    /** Kills the program running, for a pipe that is itself being stopped. */
    fun destroy() {
        running?.leg?.destroy()
    }

    /** Passes Desktop's [line] on, switching the program when it asks for a model on the other side; false when the
     *  next program could not be started. */
    private suspend fun relay(now: Running, line: String): Boolean {
        val ask = asks.of(line)
        if (ask is DesktopAsk.Initialize && initialize == null) initialize = line
        val model = (ask as? DesktopAsk.SetModel)?.model
        if (model == null || model.startsWith(ANTIGRAVITY_MODEL_PREFIX) == now.line.antigravity) {
            now.leg.send(line)
            return true
        }
        return switch(now, model, line)
    }

    /** [now] ended and the program for [model] started on the same session, given the kept `initialize` and
     *  [setModel]; false when it could not be started. */
    private suspend fun switch(now: Running, model: String, setModel: String): Boolean {
        // The running program's record first: a forked or continued session has an id of its own, not the line's.
        val id = now.leg.sessionId() ?: now.line.sessionId
        if (now.line.antigravity) now.leg.send(setModel)
        now.leg.stop(graceMs)
        if (id == null) err.line("splice desktop launch: the session's id is not known, so $model starts a new one")
        val leg = start(now.line.switchedTo(model, id)) ?: return false
        listOfNotNull(initialize, setModel).forEach(leg::send)
        return true
    }

    private fun start(line: ClaudeLine): PipeLeg? {
        val leg = try {
            legs.start(line)
        } catch (e: IOException) {
            val program = if (line.antigravity) "splice desktop acp" else line.words.first()
            err.line("splice desktop launch: $program could not be started: ${SafeFailureText.render(e)}")
            return null
        }
        running = Running(line, leg)
        scope.launch(io) { heard.send(Heard.Ended(leg, leg.ended())) }
        return leg
    }
}

/** The session's command line and the program running it. */
private data class Running(val line: ClaudeLine, val leg: PipeLeg)
