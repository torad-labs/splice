package splice.app.desktop.setup

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import splice.client.desktop.DoorHooks
import splice.client.login.DoorLogin
import splice.client.resume.AuthHeaderFile
import splice.client.resume.ResumeHookTarget
import splice.core.client.DOOR_HOOK_KEY
import splice.core.config.InstallPaths
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import splice.core.config.TurnKey
import splice.core.config.UserHome
import splice.core.util.DaemonLog
import splice.core.util.SecureFile
import java.nio.file.Path

/** Extra settings for the Desktop translator, independent of the person's Claude settings. */
internal class DesktopHooks(private val paths: StatePaths) {
    val file: Path = paths.stateDir.resolve("desktop-hooks.json")
    val foreground: Path = paths.doorLoginDir.resolve("splice-desktop-foreground.sh")

    fun lay(port: Int) {
        val header = TurnKey(MgmtKey(paths)).headerFile()
        val target = ResumeHookTarget(port, AuthHeaderFile { header })
        val door = DoorHooks(paths.doorLoginDir, DaemonLog::write)
        val start = door.sessionStartHooks(target).getValue("SessionStart")
        val login = DoorLogin(UserHome.claudeDir(paths.envReader), paths.doorLoginDir).hooks()
        val foregroundBody = DesktopShell.script(
            "desktop-foreground.sh",
            "/bin/sh",
            mapOf("url" to "http://127.0.0.1:$port/hooks/foreground/$DOOR_HOOK_KEY", "auth_file" to header.toString()),
        )
        SecureFile.writeAtomic(foreground, foregroundBody, ownerOnlyExecutable)
        val tool = DesktopShell.word(foreground.toString())
        val splice = InstallPaths(envReader = paths.envReader).binDir.resolve("splice").toAbsolutePath()
        val peer = command("${DesktopShell.word(splice.toString())} peer attach --hook")
        val hooks = buildJsonObject {
            put("SessionStart", JsonArray(start + peer))
            login.forEach { (event, groups) -> put(event, JsonArray(groups)) }
            put("PreToolUse", JsonArray(listOf(command("$tool start", "*"))))
            put("PostToolUse", JsonArray(listOf(command("$tool end", "*"))))
        }
        SecureFile.writeAtomic0600(file, buildJsonObject { put("hooks", hooks) }.toString() + "\n")
    }

    private fun command(words: String, matcher: String? = null): JsonObject = buildJsonObject {
        matcher?.let { put("matcher", it) }
        putJsonArray("hooks") {
            addJsonObject {
                put("type", "command")
                put("command", words)
            }
        }
    }
}
