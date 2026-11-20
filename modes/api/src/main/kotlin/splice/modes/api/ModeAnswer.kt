// NEW: what a mode answers a call with (modes/README.md, "The contract").
package splice.modes.api

/** A call's answer: a [Text] the model reads as the tool's result, or an [Error] it reads as a failed one. */
public sealed class ModeAnswer {
    /** What the model reads. */
    public abstract val text: String

    /** The tool's result. */
    public data class Text(override val text: String) : ModeAnswer()

    /** A failed call, which the model reads as an error result. */
    public data class Error(override val text: String) : ModeAnswer()
}
