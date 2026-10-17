// NEW: non-blocking foreground callbacks reuse the resume hook's authenticated loopback transport.
package splice.client.resume

import kotlinx.serialization.json.JsonObject
import splice.client.login.HookChmod
import splice.client.login.HookScriptFiles
import splice.core.client.FOREGROUND_OWNER_ENV
import splice.core.client.FOREGROUND_OWNER_HEADER
import splice.core.client.FOREGROUND_OWNER_LENGTH
import java.nio.file.Path

internal object ForegroundHook {
    const val SCRIPT = "splice-foreground-hook.sh"

    fun script(controlPort: Int, authHeaderFile: Path, headKey: String): String = buildString {
        appendLine("#!/usr/bin/env bash")
        appendLine("# NEW: silent, asynchronous opaque activity only; never a tool decision.")
        appendLine("exec >/dev/null 2>&1")
        appendLine("phase=\"${'$'}{1:-}\"")
        appendLine("owner=\"${'$'}{$FOREGROUND_OWNER_ENV:-}\"")
        appendLine("[ ${'$'}{#owner} -eq $FOREGROUND_OWNER_LENGTH ] || exit 0")
        appendLine("case \"${'$'}phase\" in start|end|session_end) ;; *) exit 0 ;; esac")
        appendLine(
            "payload=${'$'}(perl -MJSON::PP -e " +
                projection() + " \"${'$'}phase\") || exit 0",
        )
        appendLine("[ -n \"${'$'}payload\" ] || exit 0")
        appendLine("printf '%s' \"${'$'}payload\" | curl -sS -m 1 --connect-timeout 1 -X POST \\")
        appendLine("  -H " + quote("@$authHeaderFile") + " -H 'Content-Type: application/json' --data-binary @- \\")
        appendLine("  -H \"$FOREGROUND_OWNER_HEADER: ${'$'}owner\" \\")
        appendLine("  \"http://127.0.0.1:$controlPort/hooks/foreground/$headKey\" || true")
        appendLine("exit 0")
    }

    fun install(
        configDir: Path,
        controlPort: Int,
        authHeaderFile: Path,
        headKey: String,
        chmod: HookChmod,
    ): Map<String, List<JsonObject>> {
        val script = HookScriptFiles.writeHookScript(
            configDir,
            SCRIPT,
            script(controlPort, authHeaderFile, headKey),
            chmod,
        )
        return mapOf(
            "PreToolUse" to listOf(HookScriptFiles.asyncHookEntry(script, "start")),
            "PostToolUse" to listOf(HookScriptFiles.asyncHookEntry(script, "end")),
            "PostToolUseFailure" to listOf(HookScriptFiles.asyncHookEntry(script, "end")),
            "SessionEnd" to listOf(HookScriptFiles.asyncHookEntry(script, "session_end")),
        )
    }

    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    /** Perl's core decoder validates JSON; the alarm and byte cap bound even malformed inputs. */
    private fun projection(): String = quote(
        """
            alarm 1;
            my ${'$'}raw = "";
            while (read(STDIN, my ${'$'}chunk, 65536)) {
                ${'$'}raw .= ${'$'}chunk;
                exit 0 if length(${'$'}raw) > 8 * 1024 * 1024;
            }
            # Bounded opaque ids fit below 4096 encoded bytes. Discard longer JSON string tokens before
            # the pure-Perl decoder, so multi-megabyte tool contents cannot consume its deadline.
            ${'$'}raw =~ s/"(?:[^"\\\x00-\x1F]++|\\(?:["\\\/bfnrt]|u[0-9a-fA-F]{4}))*"/length(${'$'}&) > 4096 ? '""' : ${'$'}&/ge;
            my ${'$'}hook = decode_json(${'$'}raw);
            exit 0 unless ref(${'$'}hook) eq "HASH";
            my ${'$'}session = ${'$'}hook->{session_id};
            exit 0 unless defined(${'$'}session) && !ref(${'$'}session)
                && encode_json([${'$'}session]) =~ /^\["/ && length(${'$'}session) > 0 && length(${'$'}session) <= 128;
            my ${'$'}phase = ${'$'}ARGV[0];
            my ${'$'}tool = ${'$'}hook->{tool_use_id};
            if (${'$'}phase eq "session_end") { ${'$'}tool = undef; }
            else {
                exit 0 unless defined(${'$'}tool) && !ref(${'$'}tool)
                    && encode_json([${'$'}tool]) =~ /^\["/ && length(${'$'}tool) > 0 && length(${'$'}tool) <= 256;
            }
            print encode_json({session_id => ${'$'}session, tool_use_id => ${'$'}tool, phase => ${'$'}phase});
        """.trimIndent(),
    )
}
