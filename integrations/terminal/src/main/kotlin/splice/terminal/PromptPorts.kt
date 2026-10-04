// NEW: the ROLES the prompt toolkit injects, named (kt-no-lambda-seam). They were raw function
// types spread across TerminalMode, Spinner, SelectPrompt and MultiSelectPrompt — four spellings of
// `() -> Boolean` that all meant the same thing, which is exactly the shape-versus-role confusion
// the wall exists to stop. One role, one interface: ConsolePresence is declared ONCE here and the
// cli package imports it rather than keeping a second copy of the same concept.
package splice.terminal

/**
 * Whether a real terminal is attached.
 *
 * False in a pipe, a CI log and a test, where every widget must take its default silently instead
 * of rendering a cursor-driven menu into a log file. The single most-duplicated seam in the
 * toolkit: TerminalMode, SelectPrompt, MultiSelectPrompt and the setup wizard all ask it.
 */
public fun interface ConsolePresence {
    public operator fun invoke(): Boolean
}

/**
 * Registers a VM shutdown hook, so a SIGINT mid-prompt still restores the terminal.
 *
 * Separate from [ShutdownHookRemove] on purpose: they are opposite operations on the same registry
 * and share the `(Thread) -> Unit` shape, so an unnamed pair could be wired backwards and still
 * compile — leaving a terminal raw after Ctrl-C, which is the failure CW-9 walls.
 */
public fun interface ShutdownHookAdd {
    public operator fun invoke(hook: Thread)
}

/** Unregisters a hook registered by [ShutdownHookAdd], once its bracket has restored the mode. */
public fun interface ShutdownHookRemove {
    public operator fun invoke(hook: Thread)
}

/** The work done while the terminal is in raw mode — run exactly once, raw or not. */
internal fun interface RawBlock<T> {
    operator fun invoke(): T
}

/** One spinner tick: redraw the current frame. */
public fun interface PulseTick {
    public operator fun invoke()
}
