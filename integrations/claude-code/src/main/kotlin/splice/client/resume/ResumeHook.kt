// NEW: V4-169 (2026-09-19) — the moment the daemon learns WHICH session the `-r` picker or `-c`
// chose. At launch the daemon knows a session id only for `-r SESSION_ID`; the picker and `-c` pick
// inside Claude Code, after launch. Claude Code tells us: its SessionStart hook fires with
// `source: "resume"`, the `session_id` and the `transcript_path`. This installs that hook and the
// script POSTs the hook JSON to the daemon's /hooks/resume/<head>, which moves that transcript onto
// the head's model (TranscriptModelRewrite, the same rewrite the `-r SESSION_ID` launch runs).
//
// V4-183 (2026-09-20): the same hook also fires on `source: "startup"` — a fresh session — so the
// daemon can record which head OWNS each session (SessionOwnership) and bound a later bare `-c` to
// this head's own sessions. /clear and a compaction still never call: they start no session.
//
// NO SECRET IN THE SCRIPT, NONE IN ARGV: the script holds three literals — the control port, the head
// key and the PATH of the daemon's 0600 turn-key header file (TurnKey.headerFile), which curl reads
// with `-H @file`. curl's argv is world-readable in /proc/<pid>/cmdline, and an expanded `-H
// "Authorization: Bearer $TOKEN"` put the key there for every local user to read. The file, not the
// session's ANTHROPIC_AUTH_TOKEN (v0.4.0 review): a client-auth head plants no such variable, so a
// hook reading it exited before calling on that head, and none of its sessions was ever recorded.
//
// NEVER BLOCKS A SESSION: the script exits 0 whatever curl answers, bounded by its own -m and the
// hook timeout, and the daemon answers 200 to everything it refuses (it logs the refusal instead).
// A hook that could fail a resume would be worse than the one-line notice it exists to remove.
package splice.client.resume

import kotlinx.serialization.json.JsonObject
import splice.client.login.HookChmod
import splice.client.login.HookExecProbe
import splice.client.login.HookScriptFiles
import splice.core.util.Cancellables
import splice.core.util.DaemonLog
import splice.core.util.LogSink
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/** The SessionStart `source` value Claude Code sends for --resume, --continue and /resume — the one
 *  declaration, read by the hook installer here and by the control route that receives the call. */
public const val RESUME_SOURCE: String = "resume"

/** The SessionStart `source` value Claude Code sends for a fresh session (V4-183). */
public const val STARTUP_SOURCE: String = "startup"

internal object ResumeHook {
    const val RESUME_HOOK_SH: String = "splice-resume-hook.sh"

    // why: the hook is one loopback POST; five seconds is far above a daemon on the same box and far
    // below the hook timeout, so a stalled daemon costs a resume five seconds, never fifteen.
    private const val CURL_TIMEOUT_S = 5

    /** [headKey] is the topology key LaunchSpecFactory passes — `[A-Za-z0-9_-]`, validated at load —
     *  and it lands inside a double-quoted URL, so it is written as is. */
    fun script(controlPort: Int, authHeaderFile: Path, headKey: String): String = buildString {
        appendLine("#!/usr/bin/env bash")
        appendLine("# NEW (splice, V4-169 / V4-183): on a session start or a `--resume` / `--continue` / /resume,")
        appendLine("# tell the daemon which session this head owns, so a later bare -c resumes this head's own")
        appendLine("# session and a resumed transcript is moved onto this head's model. Authenticates with the")
        appendLine("# daemon's 0600 turn-key header file; never blocks the session (exit 0 always).")
        appendLine("curl -sS -m $CURL_TIMEOUT_S -X POST \\")
        appendLine("  -H ${shellSingleQuote("@$authHeaderFile")} \\")
        appendLine("  -H 'Content-Type: application/json' --data-binary @- \\")
        appendLine("  \"http://127.0.0.1:$controlPort/hooks/resume/$headKey\" >/dev/null 2>&1 || true")
        appendLine("exit 0")
    }

    private fun shellSingleQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    /** The hook additions for the materializer: two SessionStart entries on one script, matchers
     *  "resume" and "startup". An install failure — a header file that cannot be written included —
     *  is said and the head launches without the hook: the resume then costs the notice this hook
     *  removes, and the head's sessions go unrecorded until the next launch, nothing more. */
    fun install(
        configDir: Path,
        target: ResumeHookTarget,
        headKey: String,
        log: LogSink = LogSink(DaemonLog::write),
        chmod: HookChmod = HookChmod(Files::setPosixFilePermissions),
        execProbe: HookExecProbe? = null,
    ): Map<String, List<JsonObject>> {
        val leg = Cancellables.runCatchingCancellable {
            execProbe?.invoke(configDir, chmod)?.let { failure ->
                // SAFE-RENDER-EXEMPT[2026-09-19]: an exec-bit probe on a directory we create — the failure names that directory, never file content
                throw IOException("$configDir cannot execute a staged hook (${failure.message})")
            }
            // Written current HERE, per install: a header file removed since the last launch is back
            // before the script naming it is, and a write that fails is this hook's logged failure.
            val body = script(target.controlPort, target.authHeader.current(), headKey)
            val script = HookScriptFiles.writeHookScript(configDir, RESUME_HOOK_SH, body, chmod)
            mapOf(
                HookScriptFiles.SESSION_START to listOf(
                    HookScriptFiles.hookEntry(script, HookScriptFiles.HOOK_TIMEOUT_SECONDS, matcher = RESUME_SOURCE),
                    HookScriptFiles.hookEntry(script, HookScriptFiles.HOOK_TIMEOUT_SECONDS, matcher = STARTUP_SOURCE),
                ),
            )
        }
        if (leg.isFailure) {
            log(
                "[resume] resume hook NOT installed in $configDir " +
                    // SAFE-RENDER-EXEMPT[2026-09-19]: a staged hook copy — a FileSystemException over paths this code authored, never content
                    "(${leg.exceptionOrNull()?.message}) — a session resumed on this head keeps the model " +
                    "id of the head that wrote it, so Claude Code prints its restore notice once\n",
            )
        }
        return leg.getOrElse { emptyMap() }
    }
}
