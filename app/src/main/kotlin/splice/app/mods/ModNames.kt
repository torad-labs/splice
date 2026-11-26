// NEW: the naming rules of a mod's words (the mods v2 contract): a mod's name is a mode's, since it names the mod's MCP
// server; a tool's is a mode tool's; a page's and a command's are short kebab words.
package splice.app.mods

internal object ModNames {
    // why: maximum length of the qualified mcp__<mod>__<tool> name offered to clients.
    const val MAX_OFFERED: Int = 64

    const val MOD_RULE: String = "[a-z][a-z0-9]*, at most 40 characters"
    const val TOOL_RULE: String = "[a-z][a-z0-9_]* with no __"
    const val WORD_RULE: String = "1 to 40 characters of a-z, 0-9 and -"

    private val modName = Regex("[a-z][a-z0-9]{0,39}")
    private val toolName = Regex("[a-z][a-z0-9_]*")
    private val word = Regex("[a-z0-9-]{1,40}")

    fun isModName(name: String): Boolean = modName.matches(name)

    fun isToolName(name: String): Boolean = toolName.matches(name) && "__" !in name

    fun isWord(name: String): Boolean = word.matches(name)

    fun offered(mod: String, tool: String): String = "mcp__${mod}__$tool"

    /** What a mod may say it does (the contract's Capability). */
    val capabilities: Set<String> = setOf(
        "watch-sessions", "change-launch", "watch-turns", "change-requests", "answer-tools", "add-rows", "draw-ui",
        "run-commands", "serve-http",
    )
}
