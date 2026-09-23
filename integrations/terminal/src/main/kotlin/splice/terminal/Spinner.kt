// NEW: one-line spinner for the CLI prompt toolkit (cli-wizard CW-2).
package splice.terminal

import splice.core.terminal.GREEN
import splice.core.terminal.RESET
import java.util.Timer
import java.util.TimerTask

/** Starts the spinner's ticks; closing the handle stops them. */
public fun interface PulseScheduler {
    public fun start(onPulse: PulseTick): AutoCloseable
}

internal class TimerPulseScheduler(
    private val periodMs: Long = PULSE_MS,
) : PulseScheduler {
    override fun start(onPulse: PulseTick): AutoCloseable {
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

/** A one-line spinner; with no terminal it prints only the final line. */
public class Spinner(
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

    public fun start(message: String) {
        this.message = message
        if (!tty) return
        running = true
        frame = 0
        redraw()
        pulses = scheduler.start { pulse() }
    }

    public fun update(message: String) {
        this.message = message
        if (tty && running) redraw()
    }

    public fun stop(finalMessage: String) {
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
