// NEW: terminal launch profiles (issue #791, comment §4). `splice launch claude --profile <name>` rebuilds the
// profile's Claude folder before each launch, from the person's `~/.claude` read and never written, in layers: Claude
// Code's defaults, under the person's own config, under the profile's filters (keep_hooks, drop_hooks, keep_plugins,
// drop_blocks, drop_names), under splice's standing door values (the door's overlay and environment, which the launch
// lays on top). Every write lands inside the profile's folder: settings files, CLAUDE.md, `.claude.json`, the plugins
// it keeps, and links for skills, agents, commands, projects and sessions.
package splice.client.profile

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import splice.client.ClaudeModelLists
import splice.client.Keys
import splice.client.desktop.DesktopSettingsRead
import splice.client.desktop.SettingsRead
import splice.core.config.UserHome
import splice.core.topology.KEEP_HOOKS_ALL
import splice.core.topology.LaunchProfileConfig
import splice.core.util.SafeFailureText
import splice.core.util.SecureFile
import java.io.IOException
import java.io.UncheckedIOException
import java.nio.file.Files
import java.nio.file.Path

/** The sources a profile is rebuilt from: the person's [home], with `~/.claude` and `~/.claude.json` in it, Splice's
 *  MCP user [registry] (server name to entry, as it is at this launch), and the root a profile with no `dir` lives
 *  under. */
public class LaunchProfileBuild(
    private val home: Path,
    private val defaultRoot: Path,
    private val registry: JsonObject,
) {
    private val claudeDir = home.resolve(UserHome.CLAUDE_DIR).toAbsolutePath().normalize()
    private val json = Json { prettyPrint = true }

    /** Profile [name] rebuilt from [profile], with [doorOwned] (the variables the door sets or takes out) left out of
     *  its settings' `env`. */
    public fun build(name: String, profile: LaunchProfileConfig, doorOwned: Set<String>): ProfileBuilt {
        val folder = profile.folder(name, home, defaultRoot).toAbsolutePath().normalize()
        return try {
            ProfileFolder(claudeDir).prepare(folder, name)?.let { return ProfileBuilt.Refused(it) }
            ProfileBuilt.Ready(folder, fill(folder, profile, doorOwned))
        } catch (failure: IOException) {
            refused(name, folder, failure)
        } catch (failure: UncheckedIOException) {
            refused(name, folder, failure)
        }
    }

    private fun refused(name: String, folder: Path, failure: Exception): ProfileBuilt =
        ProfileBuilt.Refused("profile $name could not be rebuilt in $folder: ${SafeFailureText.render(failure)}")

    /** Everything the profile carries written into [folder]; what the person should know of it. */
    private fun fill(folder: Path, profile: LaunchProfileConfig, doorOwned: Set<String>): List<String> {
        val settings = ProfileSettings(profile, doorOwned)
        val kept = ClaudeModelLists.settingsFiles.flatMap { file -> settingsFile(folder, file, settings) }.toSet()
        ProfilePlugins(claudeDir, withHooks = profile.keepHooks == KEEP_HOOKS_ALL).build(folder, kept)
        val unclosed = instructions(folder, profile)
        ProfileGlobalConfig(home.resolve(Keys.CLAUDE_JSON), registry).build(folder)
        val links = ProfileLinks(claudeDir, profile.dropNames).link(folder)
        return unclosed.map { "CLAUDE.md's block \"$it\" opens and never closes, so it is kept" } + links
    }

    /** `<folder>/<file>` rebuilt from `~/.claude/<file>`, or taken away while that is not there; the plugins it keeps.
     *  A file that is there and can't be read stops the launch, so nothing it would have dropped slips through. */
    private fun settingsFile(folder: Path, file: String, settings: ProfileSettings): Set<String> {
        val target = folder.resolve(file)
        return when (val read = DesktopSettingsRead(claudeDir.resolve(file)).read()) {
            SettingsRead.Absent -> {
                Files.deleteIfExists(target)
                emptySet()
            }
            is SettingsRead.Unreadable -> throw IOException(read.why)
            is SettingsRead.Held -> {
                val filtered = settings.filtered(read.file)
                SecureFile.writeAtomic0600(target, json.encodeToString(JsonElement.serializer(), filtered))
                settings.keptPlugins(read.file)
            }
        }
    }

    /** `<folder>/CLAUDE.md`, the person's with the dropped blocks out, or none while the person has none; the markers
     *  that open there and never close. */
    private fun instructions(folder: Path, profile: LaunchProfileConfig): List<String> {
        val source = claudeDir.resolve(Keys.CLAUDE_MD)
        val target = folder.resolve(Keys.CLAUDE_MD)
        if (!Files.isRegularFile(source)) {
            Files.deleteIfExists(target)
            return emptyList()
        }
        val stripped = ProfileInstructions(profile.dropBlocks).stripped(Files.readString(source))
        SecureFile.writeAtomic0600(target, stripped.text)
        return stripped.unclosed
    }
}

/** What rebuilding a profile came to. */
public sealed class ProfileBuilt {
    /** The profile's [folder], ready, and [notes] the person should hear before the session starts. */
    public data class Ready(val folder: Path, val notes: List<String>) : ProfileBuilt()

    /** Nothing launches, for [reason]. */
    public data class Refused(val reason: String) : ProfileBuilt()
}
