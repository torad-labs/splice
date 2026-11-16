// NEW: gpt-speed (docs/specs/gpt-speed.md, decision 2) — the UserPromptSubmit hook that catches /fast and /ultrafast
// before the model. The daemon holds each session's speed, so the script hands it the hook JSON (POST
// /hooks/speed/<head>) and prints what it answers: a decision that stops the prompt with the reply the person reads,
// or `{}` for a prompt that is not a speed command. Nothing reaches the model and no turn is spent.
//
// CHEAP ON EVERY OTHER PROMPT: the script calls the daemon only when the prompt opens with /fast, /ultrafast or the
// skill's sentinel; every other prompt costs a bash glob. The daemon decides what is a command (`/fastidious` is not).
//
// A DAEMON THAT DOES NOT ANSWER still stops an exact command, saying the speed is unchanged: a /fast that went on to the
// model would be read as a request to it, and the person would never learn the switch did not happen.
//
// NO SECRET IN THE SCRIPT, NONE IN ARGV: the control port, the head key and the PATH of the daemon's 0600 turn-key
// header file, which curl reads with `-H @file` (the resume hook's reason, ResumeHook).
package splice.client.speed

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.client.login.HookScriptFiles
import splice.client.login.HookScriptFiles.shellSingleQuote
import splice.client.login.LoginInterception
import splice.client.resume.DaemonHook
import splice.client.resume.ResumeHook
import splice.core.util.JsonWire
import java.nio.file.Path

internal object SpeedHook : DaemonHook {
    const val SPEED_HOOK_SH: String = "splice-speed-hook.sh"

    override val name: String = "speed"
    override val fileName: String = SPEED_HOOK_SH
    override val withoutIt: String = "/fast and /ultrafast reach the model as words on this launch"

    private val unreachable = JsonWire.string(
        buildJsonObject {
            put("decision", "block")
            put(
                "reason",
                "splice's daemon did not answer, so this session's speed is unchanged. Try again once it is up.",
            )
        },
    )

    override fun script(controlPort: Int, authHeaderFile: Path, headKey: String): String = buildString {
        val d = "$"
        appendLine("#!/usr/bin/env bash")
        appendLine("# NEW (splice, gpt-speed): /fast and /ultrafast set this session's speed in the daemon, before the")
        appendLine("# model sees them. Every other prompt passes untouched.")
        appendLine("input=\"$d(cat)\"")
        appendLine("case \"${d}input\" in")
        for (opening in listOf("/fast", "/ultrafast", "$SPEED_SENTINEL ")) {
            appendLine("  *'\"prompt\":\"$opening'*|*'\"prompt\": \"$opening'*) ;;")
        }
        appendLine("  *) exit 0 ;;")
        appendLine("esac")
        appendLine("out=\"$d(printf '%s' \"${d}input\" | curl -sSf -m ${ResumeHook.CURL_TIMEOUT_S} -X POST \\")
        appendLine("  -H ${shellSingleQuote("@$authHeaderFile")} \\")
        appendLine("  -H 'Content-Type: application/json' --data-binary @- \\")
        appendLine("  \"http://127.0.0.1:$controlPort/hooks/speed/$headKey\" 2>/dev/null)\"")
        appendLine("if [ -n \"${d}out\" ]; then printf '%s' \"${d}out\"; exit 0; fi")
        // The daemon did not answer: an exact command is still stopped, anything else goes on.
        appendLine("re='\"prompt\": ?\"(/|$SPEED_SENTINEL )(fast|ultrafast)( [A-Za-z]*)?\"'")
        appendLine("if [[ ${d}input =~ ${d}re ]]; then printf '%s' ${shellSingleQuote(unreachable)}; fi")
        appendLine("exit 0")
    }

    /** One UserPromptSubmit entry. */
    override fun events(script: Path): Map<String, List<JsonObject>> = mapOf(
        LoginInterception.USER_PROMPT_SUBMIT to listOf(
            HookScriptFiles.hookEntry(script, HookScriptFiles.HOOK_TIMEOUT_SECONDS),
        ),
    )
}
