// NEW: held-status review, 2026-11-17 — the one table of the colours a capability may declare and how the terminal
// draws each. The declaration check accepts exactly its names and the status line draws a held turn's message from its
// escapes, so a name added here is both accepted at boot and drawn; neither can know a name the other does not.
package splice.core.capability

/** The status line's own colour names, the terminal's eight and gray, each with the escape that draws it. */
public object CapabilityColors {
    public val escapes: Map<String, String> = mapOf(
        "black" to "\u001B[30m",
        "red" to "\u001B[31m",
        "green" to "\u001B[32m",
        "yellow" to "\u001B[33m",
        "blue" to "\u001B[34m",
        "magenta" to "\u001B[35m",
        "cyan" to "\u001B[36m",
        "white" to "\u001B[37m",
        "gray" to "\u001B[90m",
    )
}
