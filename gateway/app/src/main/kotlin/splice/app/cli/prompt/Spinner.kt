// NEW: one-line spinner for the CLI prompt toolkit (cli-wizard CW-2).
package splice.app.cli.prompt

import splice.app.cli.GREEN
import splice.app.cli.RESET
import java.util.Timer
import java.util.TimerTask

internal fun interface PulseScheduler {
    fun start(onPulse: () -> Unit): AutoCloseable
}

internal class TimerPulseScheduler(
    private val periodMs: Long = PULSE_MS,
) : PulseScheduler {
    override fun start(onPulse: () -> Unit): AutoCloseable {
        val timer = Timer("splice-spinner", true)
        timer.schedule(
            object : TimerTask() {
                override fun run() = onPulse()
            },
            periodMs,
            periodMs,
        )
        return AutoCloseable { timer.cancel() }
    }
}

internal class Spinner(
    private val out: Appendable = System.out,
    private val tty: Boolean = System.console() != null,
    private val scheduler: PulseScheduler = TimerPulseScheduler(),
) {
    private val frames = listOf(
        "⠋", "⠙", "⠹", "⠸", "⠼",
        "⠴", "⠦", "⠧", "⠇", "⠏",
    )
    private var message = ""
    private var frame = 0
    private var running = false
    private var pulses: AutoCloseable? = null

    fun start(message: String) {
        this.message = message
        if (!tty) return
        running = true
        frame = 0
        redraw()
        pulses = scheduler.start { pulse() }
    }

    fun update(message: String) {
        this.message = message
        if (tty && running) redraw()
    }

    fun stop(finalMessage: String) {
        running = false
        pulses?.close()
        pulses = null
        if (!tty) {
            out.append(finalMessage).append('\n')
            return
        }
        out.append(ERASE)
        out.append(GREEN).append(CHECK).append(RESET)
        out.append(' ').append(finalMessage).append('\n')
    }

    internal fun pulse() {
        if (!running || !tty) return
        frame = (frame + 1) % frames.size
        redraw()
    }

    private fun redraw() {
        out.append(ERASE).append(frames[frame]).append(' ').append(message)
    }
}

private const val PULSE_MS = 80L
private const val CHECK = "✓"
private const val ERASE = "\r\u001B[2K"
