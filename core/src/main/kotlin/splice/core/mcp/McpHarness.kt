package splice.core.mcp

import splice.core.harness.HarnessDefinition
import splice.core.harness.HarnessProtocol

/**
 * The shipped harnesses and their MCP sync adapters. [wire] and [displayName] keep the MCP
 * registry and CLI vocabulary; [definition] is the same harness on the Desktop launcher wire.
 */
public enum class McpHarness(
    public val wire: String,
    public val displayName: String,
    public val definition: HarnessDefinition,
) {
    CLAUDE(
        "claude",
        "Claude Code / Desktop",
        HarnessDefinition(
            "claude-code",
            "Claude Code (Splice)",
            "Claude Code started through Splice's gateway, on the model the session picked",
            HarnessProtocol.CLAUDE_CODE,
        ),
    ),
    CODEX(
        "codex",
        "Codex",
        HarnessDefinition(
            "codex",
            "Codex CLI",
            "OpenAI's Codex CLI, started by a session launcher that reads Splice's harness map",
            HarnessProtocol.CODEX_APP_SERVER,
        ),
    ),
    AGY(
        "agy",
        "Antigravity (agy)",
        HarnessDefinition(
            "antigravity",
            "Antigravity",
            "Google's agy, started through `splice desktop acp` in place of Claude Code",
            HarnessProtocol.ACP,
        ),
    ),
}
