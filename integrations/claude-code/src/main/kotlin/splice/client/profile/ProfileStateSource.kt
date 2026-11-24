package splice.client.profile

import splice.client.CLAUDE_AI_OAUTH
import splice.client.CREDENTIALS_FILE
import splice.client.Keys
import splice.client.desktop.DesktopSettingsRead
import splice.client.desktop.SettingsRead
import splice.core.config.UserHome
import splice.core.topology.LaunchProfileConfig
import splice.core.topology.profile.PROFILE_STATE_PATH_RULE
import splice.core.topology.profile.ProfileStatePaths
import java.nio.file.Path

/** Read the seed from the explicit file, the shell's personal Claude directory, or the home fallback. */
internal class ProfileStateSource(
    private val home: Path,
    defaultRoot: Path,
    headDirs: List<Path>,
    folder: Path,
) {
    private val paths = ProfileStatePaths(home, defaultRoot, headDirs, folder)
    private val fallback = home.resolve(Keys.CLAUDE_JSON)

    fun read(profile: LaunchProfileConfig, inherited: String?): SettingsRead {
        val named = profile.claudeJson
        val label = named?.let { "claude_json '$it'" } ?: "state source"
        val path = (if (named == null) implicit(inherited) else paths.named(named))
            ?: return SettingsRead.Unreadable("$label $PROFILE_STATE_PATH_RULE")
        return readState(path, label)
    }

    private fun readState(path: Path, label: String): SettingsRead {
        if (path.fileName?.toString()?.endsWith(CREDENTIALS_FILE) == true) {
            return credentialsRefusal(label, path)
        }
        return when (val read = DesktopSettingsRead(path).read()) {
            SettingsRead.Absent -> read
            is SettingsRead.Unreadable -> SettingsRead.Unreadable("$label: ${read.why}")
            is SettingsRead.Held -> if (CLAUDE_AI_OAUTH in read.file) credentialsRefusal(label, path) else read
        }
    }

    private fun implicit(inherited: String?): Path? {
        val fromShell = inherited?.let { UserHome.declared(it, home) }?.resolve(Keys.CLAUDE_JSON)
        return paths.allowed(fromShell) ?: paths.allowed(fallback)
    }

    private fun credentialsRefusal(label: String, path: Path): SettingsRead.Unreadable =
        SettingsRead.Unreadable(
            "$label points to Claude credentials at $path; select a .claude.json state file instead",
        )
}
