// NEW: terminal launch profiles (issue #791, comment §4). A profile's `.claude.json`, which Claude Code reads in place
// of the person's `~/.claude.json` once CLAUDE_CONFIG_DIR names the profile: what Claude Code already wrote there in
// earlier sessions of the profile is kept, what it lacks is seeded from the person's own file (onboarding, folder
// trust, each project's settings), and `mcpServers` is rebuilt at every launch: the person's own servers, and Splice's
// MCP user registry's where the person has none of that name. A Claude login is never carried: the door's key is the
// session's. Nor is a picker cache (additionalModelOptionsCache): the gateway's /v1/models is the one list. The
// file can hold servers' keys, so it is written owner-only, and no value in it is ever said.
package splice.client.profile

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import splice.client.ClaudeModelLists
import splice.client.Keys
import splice.client.desktop.DesktopSettingsRead
import splice.client.desktop.SettingsRead
import splice.core.util.SecureFile
import java.io.IOException
import java.nio.file.Path

internal class ProfileGlobalConfig(
    /** The person's Claude state, already read and validated. */
    private val personal: JsonObject?,
    /** Splice's MCP user registry, server name to entry. */
    private val registry: JsonObject,
) {
    private val json = Json { prettyPrint = true }

    /** `<folder>/.claude.json` rebuilt; the names of the MCP servers it carries. */
    fun build(folder: Path): Set<String> {
        val file = folder.resolve(Keys.CLAUDE_JSON)
        val own = read(file).orEmpty()
        val yours = personal.orEmpty().filterKeys { it !in NEVER_CARRIED }
        val seeded = (yours.filterKeys { it !in own } + own).filterKeys { it != ClaudeModelLists.STATE_CACHE }
        val projects = JsonObject(objectAt(yours, Keys.PROJECTS) + objectAt(own, Keys.PROJECTS))
        val servers = JsonObject(registry + objectAt(yours, Keys.MCP_SERVERS))
        val built = JsonObject(seeded + (Keys.PROJECTS to projects) + (Keys.MCP_SERVERS to servers))
        SecureFile.writeAtomic0600(file, json.encodeToString(JsonElement.serializer(), built))
        return servers.keys
    }

    private fun objectAt(config: Map<String, JsonElement>, key: String): Map<String, JsonElement> =
        (config[key] as? JsonObject).orEmpty()

    /** The profile's own state as Claude Code left it. Never replace a copy that can't be read. */
    private fun read(file: Path): JsonObject? = when (val read = DesktopSettingsRead(file).read()) {
        SettingsRead.Absent -> null
        is SettingsRead.Held -> read.file
        is SettingsRead.Unreadable -> throw IOException(read.why)
    }
}

// The Claude login Claude Code keeps in the file, which a session on the door never uses.
private val NEVER_CARRIED = setOf("oauthAccount", "primaryApiKey")
