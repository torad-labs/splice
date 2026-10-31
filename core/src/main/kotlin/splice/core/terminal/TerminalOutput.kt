// NEW: the one operator-terminal line port (LAYOUT-01). Feature and integration code may not
// `println` (kt-no-println), so a command's operator-facing lines leave through this; the CLI hands
// in a stdout writer and a test hands in a recorder, and neither mutates `System.out`.
package splice.core.terminal

/** Writes one operator-facing terminal line — not a daemon diagnostic and not a client-wire frame. */
public fun interface TerminalOutput {
    public fun line(text: String)
}
