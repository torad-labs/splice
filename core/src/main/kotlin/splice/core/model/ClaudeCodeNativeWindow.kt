package splice.core.model

/** The native-million families in Claude Code's built-in model table. They can use 1M without a beta. */
public object ClaudeCodeNativeWindow {
    public fun of(model: String): Long? =
        CLAUDE_CODE_ONE_MILLION.takeIf { NATIVE_MILLION.containsMatchIn(model) }
}

// Claude Code 2.1.332 also recognizes these families in provider-qualified and versioned spellings.
private val NATIVE_MILLION = Regex(
    """(?:^|--|\.)claude-(?:fable-5|mythos-5|sonnet-5|opus-(?:4-[78]|5)|haiku-5-5)(?!\d)""",
    RegexOption.IGNORE_CASE,
)
