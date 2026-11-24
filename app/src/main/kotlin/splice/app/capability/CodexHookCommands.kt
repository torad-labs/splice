// NEW: parses, validates, and migrates Codex peer hook command strings (`splice peer codex attach|detach`),
// preserving custom binary paths and CLI flags while stripping legacy `sh -c`/`jq` wrappers.
package splice.app.capability

/** Recognizes and migrates `splice peer codex attach|detach` hook commands without clobbering user customizations. */
internal object CodexHookCommands {
    private val directPattern = Regex(
        """^(?:"[^"]*splice"|'[^']*splice'|(?:\S*/)?splice)\s+peer\s+codex\s+(attach|detach)(?:\s|$)""",
    )
    private val embeddedPattern = Regex(
        """(?:"[^"]*splice"|'[^']*splice'|(?:\S*/)?splice)\s+peer\s+codex\s+(?:attach|detach)[^;|&'\n]*""",
    )
    private val trailingShellVar = Regex("""\s+(?:"\$\{?[A-Za-z_][A-Za-z0-9_]*\}?"|\$\{?[A-Za-z_][A-Za-z0-9_]*\}?)$""")
    private val shellMarkers = listOf("sh -c", "bash -c", "jq", "$(", "`", ";", "|", "&&")

    fun isSpliceCommand(command: String): Boolean =
        "splice peer codex" in command || "peer codex attach" in command || "peer codex detach" in command

    /** True when [command] invokes `splice peer codex <verb>` directly (with any binary path or extra flags). */
    fun isValidDirect(command: String, verb: String): Boolean {
        val trimmed = command.trim()
        if (isLegacyShell(trimmed)) return false
        return directPattern.find(trimmed)?.groupValues?.getOrNull(1) == verb
    }

    /** Preserves an existing valid direct command verbatim, or unwraps a legacy shell wrapper keeping binary & flags. */
    fun migrate(existing: String?, verb: String): String {
        val trimmed = existing?.trim().orEmpty()
        return when {
            trimmed.isEmpty() -> "splice peer codex $verb"
            isValidDirect(trimmed, verb) -> trimmed
            else -> unwrapLegacy(trimmed, verb)
        }
    }

    private fun unwrapLegacy(trimmed: String, verb: String): String {
        val extracted = embeddedPattern.find(trimmed)?.value?.trim() ?: return "splice peer codex $verb"
        val cleaned = trailingShellVar.replace(extracted, "").trim()
        return cleaned.replaceFirst(Regex("""\bpeer\s+codex\s+(attach|detach)\b"""), "peer codex $verb")
    }

    private fun isLegacyShell(command: String): Boolean = shellMarkers.any { it in command }
}
