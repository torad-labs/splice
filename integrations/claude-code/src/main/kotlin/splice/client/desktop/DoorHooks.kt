// NEW: door-level Claude Code hooks staged inside splice's own state folder (never touching `~/.claude` or laying
// skills in `~/.claude/skills`):
// - `/fast` and `/ultrafast` (`SpeedHook` -> `/hooks/speed/door`) when a ChatGPT/Codex provider is in the lineup
//   (`/fast` passes through untouched on Claude/non-ChatGPT sessions or before a ChatGPT turn runs), and
// - `SessionStart` (`DoorStartHook` -> `/hooks/resume/door`) so Teams cwd rule-binding sees door sessions.
package splice.client.desktop

import kotlinx.serialization.json.JsonObject
import splice.client.login.HookInstaller
import splice.client.login.HookScriptFiles
import splice.client.login.HookScriptFiles.shellSingleQuote
import splice.client.login.LoginInterception
import splice.client.resume.DaemonHook
import splice.client.resume.ResumeHook
import splice.client.resume.ResumeHookTarget
import splice.client.resume.STARTUP_SOURCE
import splice.client.speed.SpeedHook
import splice.core.client.DOOR_HOOK_KEY
import splice.core.client.FOREGROUND_OWNER_ENV
import splice.core.client.FOREGROUND_OWNER_HEADER
import splice.core.util.Cancellables
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import java.nio.file.Files
import java.nio.file.Path

/** Stages door hook scripts into [hookDir] (inside splice's state folder) and merges overlay hook maps. */
public class DoorHooks(private val hookDir: Path, private val log: LogSink) {
    private val installer = HookInstaller(log)

    /** UserPromptSubmit hook for `/fast` and `/ultrafast`, calling `POST /hooks/speed/door`. */
    public fun speedHooks(target: ResumeHookTarget): Map<String, List<JsonObject>> = stage(SpeedHook, target)

    /** SessionStart hook for door sessions, calling `POST /hooks/resume/door` for Teams cwd rule-binding. */
    public fun sessionStartHooks(target: ResumeHookTarget): Map<String, List<JsonObject>> = stage(DoorStartHook, target)

    /** Merges multiple event->entries hook maps without overwriting duplicate event keys. */
    public fun merge(vararg maps: Map<String, List<JsonObject>>): Map<String, List<JsonObject>> =
        maps.fold(emptyMap()) { acc, next -> LoginInterception.concat(acc, next) }

    private fun stage(hook: DaemonHook, target: ResumeHookTarget): Map<String, List<JsonObject>> =
        Cancellables.runCatchingCancellable {
            hook.install(Files.createDirectories(hookDir), target, DOOR_HOOK_KEY, installer)
        }.getOrElse { failure ->
            log("[${hook.name}] door ${hook.name} hook NOT staged in $hookDir: ${SafeFailureText.render(failure)}\n")
            emptyMap()
        }
}

private object DoorStartHook : DaemonHook {
    const val DOOR_START_HOOK_SH: String = "splice-door-start-hook.sh"

    override val name: String = "door-start"
    override val fileName: String = DOOR_START_HOOK_SH
    override val withoutIt: String = "new door sessions do not notify splice of their starting folder"

    override fun script(controlPort: Int, authHeaderFile: Path, headKey: String): String = buildString {
        appendLine("#!/usr/bin/env bash")
        appendLine("# NEW (splice): on a door session startup, notify the daemon of session_id and cwd for")
        appendLine("# Teams rule-binding and foreground tracking. Never blocks the session (exit 0 always).")
        appendLine("curl -sS -m ${ResumeHook.CURL_TIMEOUT_S} -X POST \\")
        appendLine("  -H ${shellSingleQuote("@$authHeaderFile")} \\")
        appendLine("  -H 'Content-Type: application/json' --data-binary @- \\")
        appendLine("  -H \"$FOREGROUND_OWNER_HEADER: $" + "{$FOREGROUND_OWNER_ENV:-}\" \\")
        appendLine("  \"http://127.0.0.1:$controlPort/hooks/resume/$headKey\" >/dev/null 2>&1 || true")
        appendLine("exit 0")
    }

    override fun events(script: Path): Map<String, List<JsonObject>> = mapOf(
        HookScriptFiles.SESSION_START to listOf(
            HookScriptFiles.hookEntry(script, HookScriptFiles.HOOK_TIMEOUT_SECONDS, matcher = STARTUP_SOURCE),
        ),
    )
}
