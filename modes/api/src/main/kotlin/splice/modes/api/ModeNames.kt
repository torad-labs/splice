// NEW: the naming rules every mode's words follow (modes/README.md, "Names"). A mode's name is one word that is its
// folder, its Gradle id (:modes-<name>) and its package segment (splice.modes.<name>); a server and a tool are what a
// provider's tool name can hold, and the model sees each tool as mcp__<server>__<tool>.
package splice.modes.api

internal object ModeNames {
    /** Anthropic's limit on a tool's name, which the offered name must keep. */
    const val MAX_OFFERED: Int = 64

    private val modeName = Regex("[a-z][a-z0-9]*")
    private val toolName = Regex("[a-z][a-z0-9_]*")

    /** Whether [name] can be a mode's name: a lowercase letter, then lowercase letters and digits. */
    fun isModeName(name: String): Boolean = modeName.matches(name)

    /** Whether [name] can be a server or a tool: a lowercase letter, then lowercase letters, digits and `_`, with no
     *  `__` in it. */
    fun isToolName(name: String): Boolean = toolName.matches(name) && "__" !in name

    /** The name the model sees a tool as. */
    fun offered(server: String, tool: String): String = "mcp__${server}__$tool"
}
