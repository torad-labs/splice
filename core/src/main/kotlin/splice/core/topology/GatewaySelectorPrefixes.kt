package splice.core.topology

/** Names the gateway understands without a legacy head table. */
public object GatewaySelectorPrefixes {
    public fun canonical(provider: String): String = "claude-$provider--"

    public val legacy: Map<String, String> = mapOf(
        "claude-codex--" to "codex",
        "claude-grok--" to "xai",
        "claude-kimi--" to "kimi",
        "claude-muse--" to "muse",
        "claude-openrouter--" to "openrouter",
        "claude-fireworks--" to "fireworks",
        "claude-deepseek--" to "deepseek",
        "claude--" to "anthropic",
    )
}
