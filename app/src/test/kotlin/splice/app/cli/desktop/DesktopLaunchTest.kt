// desktop-full (df-managed-launcher): `splice desktop launch`, what a managed processWrapper starts the Code tab's Claude
// Code through once Desktop has stripped every credential from the launch. A launch on this splice's door gets the
// desktop key where Claude Code reads it, any other launch runs as it came, and the command keeps its own arguments and
// exit code, with no daemon running.
package splice.app.cli.desktop

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.desktop.record.DesktopKey
import splice.core.terminal.TerminalOutput
import splice.core.util.EnvReader
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path

class DesktopLaunchTest(@param:TempDir private val tmp: Path) {
    private val door = "http://127.0.0.1:$PORT/door"
    private val out = tmp.resolve("out.txt")
    private val said = mutableListOf<String>()

    /** What Desktop hands a managed wrapper: every credential stripped, the base address [vars] name. */
    private fun launch(vars: Map<String, String>, vararg command: String): Int {
        val env = mapOf(
            "XDG_CONFIG_HOME" to tmp.resolve("config").toString(),
            "HOME" to tmp.resolve("home").toString(),
            "SPLICE_CONTROL_PORT" to PORT.toString(),
            "CLAUDE_CODE_PROVIDER_MANAGED_BY_HOST" to "1",
        ) + vars
        return runBlocking {
            DesktopLaunch(EnvReader { env[it] }, TerminalOutput { said += it }, acp = acp, input = nothing())
                .run(listOf(STATE_DIR_FLAG, tmp.resolve("state").toString()) + command)
        }
    }

    /** A command that writes the variable [name] as it ran with it to [out]. */
    private fun printing(name: String) = arrayOf("sh", "-c", "printf %s \"\${$name-}\" > \"\$1\"", "sh", out.toString())

    private fun key() = DesktopKey(tmp.resolve("state/desktop-key")) {}.get()

    /** A stand-in for `splice desktop acp`: writes the arguments it was started with, and the bearer it was given. */
    private val acp = listOf("sh", "-c", "printf '%s\\n' \"\${ANTHROPIC_AUTH_TOKEN-UNSET}\" \"\$@\" > '$out'", "acp")

    private fun nothing() = ByteArrayInputStream(ByteArray(0))

    @Test
    fun `a session on an Antigravity model runs on splice desktop acp in place of Claude Code, with no key`() {
        val claude = "/opt/claude-code/2.1.300/claude"
        val args = listOf("--output-format", "stream-json", "--input-format", "stream-json")
        val model = "--model=claude-antigravity--neon"
        assertEquals(0, launch(mapOf(GATEWAY to door), claude, *args.toTypedArray(), model))
        assertEquals(
            listOf("UNSET", "--stands-in-for=$claude") + args + model,
            Files.readAllLines(out),
        )
    }

    @Test
    fun `a stream-json session on any other model still runs Claude Code, keyed on the door`() {
        val script = "printf %s \"\${ANTHROPIC_AUTH_TOKEN-}\" > \"\$0\""
        val code = launch(mapOf(GATEWAY to door), "sh", "-c", script, out.toString(), "--input-format", "stream-json")
        assertEquals(0, code)
        assertEquals(key(), Files.readString(out))
    }

    @Test
    fun `a Mantle launch on this splice's door gets the desktop key as its bearer`() {
        assertEquals(0, launch(mapOf(MANTLE to door), *printing("AWS_BEARER_TOKEN_BEDROCK")))
        assertEquals(key(), Files.readString(out))
    }

    @Test
    fun `a gateway launch on the door gets the key in the variable Desktop names, the bearer by default`() {
        assertEquals(0, launch(mapOf(GATEWAY to "$door/"), *printing("ANTHROPIC_AUTH_TOKEN")))
        assertEquals(key(), Files.readString(out))
        val apiKey = mapOf(GATEWAY to door, "CLAUDE_CODE_HOST_AUTH_ENV_VAR" to "ANTHROPIC_API_KEY")
        assertEquals(0, launch(apiKey, *printing("ANTHROPIC_API_KEY")))
        assertEquals(key(), Files.readString(out))
    }

    @Test
    fun `a launch anywhere else runs as it came, with no key of splice's`() {
        for (vars in listOf(
            mapOf(MANTLE to "https://mantle.example.invalid/v1"),
            mapOf(MANTLE to "http://127.0.0.1:${PORT + 1}/door"),
            mapOf(GATEWAY to "http://127.0.0.1:$PORT/v1"),
            emptyMap(),
        )) {
            assertEquals(0, launch(vars, *printing("AWS_BEARER_TOKEN_BEDROCK")), vars.toString())
            // as it came: whatever the environment it was started from held, and nothing of splice's
            assertEquals(System.getenv("AWS_BEARER_TOKEN_BEDROCK").orEmpty(), Files.readString(out), vars.toString())
            assertEquals(false, Files.readString(out) == key() && key().isNotEmpty(), vars.toString())
        }
    }

    @Test
    fun `the command keeps its own arguments and exit code, so it can sit in front of another wrapper`() {
        val script = "printf '%s|%s' \"\$1\" \"\$2\" > '$out'; exit 7"
        assertEquals(7, launch(mapOf(MANTLE to door), "sh", "-c", script, "wrapper", "two words", "--flag"))
        assertEquals("two words|--flag", Files.readString(out))
    }

    @Test
    fun `a command that can't be started is said, with a shell's exit`() {
        assertEquals(127, launch(emptyMap(), tmp.resolve("no-such-program").toString()))
        assertEquals(true, said.single().contains("could not be started"), said.toString())
    }

    @Test
    fun `side-by-side launch reads real splice toml and strips side-by-side env vars from child`() {
        val customPort = PORT + 100
        val home = Files.createDirectories(tmp.resolve("home"))
        val spliceConfig = Files.createDirectories(home.resolve(".config/splice")).resolve("splice.toml")
        Files.writeString(spliceConfig, "[daemon]\ncontrol_port = $customPort\n")
        val sideBySideDir = Files.createDirectories(tmp.resolve("config/claude-splice")).toString()
        val vars = mapOf(
            "HOME" to home.toString(),
            "XDG_CONFIG_HOME" to sideBySideDir,
            "CHROME_DESKTOP" to "ai.torad.claude-splice.desktop",
            "CLAUDE_SPLICE_DESKTOP" to "1",
            "CLAUDE_CODE_DISABLE_EXPERIMENTAL_BETAS" to "1",
            GATEWAY to "http://127.0.0.1:$customPort/door",
            "CLAUDE_CODE_PROVIDER_MANAGED_BY_HOST" to "1",
        )
        val inherited = mapOf(
            "XDG_CONFIG_HOME" to sideBySideDir,
            "CHROME_DESKTOP" to "ai.torad.claude-splice.desktop",
            "CLAUDE_SPLICE_DESKTOP" to "1",
            "CLAUDE_CODE_DISABLE_EXPERIMENTAL_BETAS" to "1",
            "KEEP_VAR" to "kept",
        )
        val script = "printf '%s|%s|%s|%s|%s|%s' " +
            "\"\${ANTHROPIC_AUTH_TOKEN-UNSET}\" \"\${XDG_CONFIG_HOME-UNSET}\" \"\${CHROME_DESKTOP-UNSET}\" " +
            "\"\${CLAUDE_SPLICE_DESKTOP-UNSET}\" \"\${CLAUDE_CODE_DISABLE_EXPERIMENTAL_BETAS-UNSET}\" " +
            "\"\${KEEP_VAR-UNSET}\" > \"\$1\""
        val exit = runBlocking {
            DesktopLaunch(EnvReader { vars[it] }, TerminalOutput { said += it }, inherited)
                .run(listOf(STATE_DIR_FLAG, tmp.resolve("state").toString(), "sh", "-c", script, "sh", out.toString()))
        }
        assertEquals(0, exit)
        assertEquals("${key()}|UNSET|UNSET|UNSET|UNSET|kept", Files.readString(out))
    }

    @Test
    fun `normal launch keeps inherited XDG and desktop environment variables`() {
        val inherited = mapOf(
            "XDG_CONFIG_HOME" to tmp.resolve("config").toString(),
            "CHROME_DESKTOP" to "claude.desktop",
            "CLAUDE_CODE_DISABLE_EXPERIMENTAL_BETAS" to "1",
        )
        val script = "printf '%s|%s|%s' " +
            "\"\${XDG_CONFIG_HOME-UNSET}\" \"\${CHROME_DESKTOP-UNSET}\" " +
            "\"\${CLAUDE_CODE_DISABLE_EXPERIMENTAL_BETAS-UNSET}\" > \"\$1\""
        val env = mapOf(
            "XDG_CONFIG_HOME" to tmp.resolve("config").toString(),
            "HOME" to tmp.resolve("home").toString(),
            "SPLICE_CONTROL_PORT" to PORT.toString(),
            GATEWAY to door,
        )
        val exit = runBlocking {
            DesktopLaunch(EnvReader { env[it] }, TerminalOutput { said += it }, inherited)
                .run(listOf(STATE_DIR_FLAG, tmp.resolve("state").toString(), "sh", "-c", script, "sh", out.toString()))
        }
        assertEquals(0, exit)
        assertEquals("${tmp.resolve("config")}|claude.desktop|1", Files.readString(out))
    }
}

private const val PORT = 47_913
private const val MANTLE = "ANTHROPIC_BEDROCK_MANTLE_BASE_URL"
private const val GATEWAY = "ANTHROPIC_BASE_URL"
