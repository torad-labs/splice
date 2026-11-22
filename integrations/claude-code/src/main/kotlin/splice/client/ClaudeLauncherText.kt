// NEW: head-catalog (gw-check). How a launcher script starts Claude Code, read from its text and never run: whether it
// runs `claude` (by that word as a command, or by a path ending `/claude`) or starts Claude Code through another
// router (claude-code-router's `ccr code`), the variables it sets that point a session past the Splice gateway (a base
// URL, a credential, a provider switch, a Bedrock or Vertex base URL), and the Claude folder it names in
// CLAUDE_CONFIG_DIR. Facts of Claude Code's dialect and of the routers people put in front of it, so they live here.
package splice.client

/** What a launcher's text says: the past-the-gateway variables it [sets], the [paths] it runs Claude Code by, whether
 *  it starts Claude Code through another [router], and the Claude folder it names ([configDir], as written). */
public data class LauncherReading(
    val sets: List<String>,
    val paths: List<String>,
    val router: Boolean,
    val configDir: String?,
)

public object ClaudeLauncherText {
    // `claude` as a command word, or a path ending in `/claude`, with what it is run by captured
    private val runsClaude = Regex(
        """(?:^|[\s;&|(`"'])((?:[^\s;&|()`"']*/)?)claude(?=$|[\s;&|)`"'])""",
        RegexOption.MULTILINE,
    )

    // claude-code-router's launcher
    private val router = Regex("""(?:^|[\s;&|(`])ccr\s+code(?=$|[\s;&|)`])""", RegexOption.MULTILINE)

    // a variable that sends a session somewhere other than the Splice gateway, as it is set
    private val past = Regex(
        """(?<![A-Za-z0-9_])(ANTHROPIC_BASE_URL|ANTHROPIC_AUTH_TOKEN|ANTHROPIC_API_KEY|CLAUDE_CODE_USE_[A-Z_]+|""" +
            """ANTHROPIC_(?:BEDROCK|VERTEX)_[A-Z_]*BASE_URL)=""",
    )

    private val configDir = Regex("""(?<![A-Za-z0-9_])CLAUDE_CONFIG_DIR=("[^"]*"|'[^']*'|[^\s;&|]+)""")

    /** [text], a launcher script, read with its comment lines taken out; null when it does not start Claude Code. */
    public fun read(text: String): LauncherReading? {
        val code = text.lines().filterNot { it.trimStart().startsWith("#") }.joinToString("\n")
        val runs = runsClaude.findAll(code).toList()
        val routed = router.containsMatchIn(code)
        if (runs.isEmpty() && !routed) return null
        return LauncherReading(
            sets = past.findAll(code).map { it.groupValues[1] }.distinct().toList(),
            paths = runs.map { it.groupValues[1] }.filter { it.isNotEmpty() }.map { "${it}claude" }.distinct(),
            router = routed,
            configDir = configDir.find(code)?.groupValues?.get(1)?.trim('"', '\''),
        )
    }
}
