// NEW: MCP hub step 6 (docs/specs/mcp-hub.md, proof f): one Claude Code version's own answer to whether
// `--plugin-dir-no-mcp` still loads a plugin with its MCP off and its skills kept. It is proof (f) made small enough
// to run unattended. The version's binary runs once in print mode, in a throwaway home and folder under the temp
// root, against a stand-in model splice serves on loopback for this run only. The plugin is a throwaway too: one
// skill, and an .mcp.json whose only server is a shell command that leaves a marker file. The flag holds when the
// binary asked the model with the plugin's skill named `<plugin>:<skill>` and the marker never appeared, because
// Claude Code starts a read .mcp.json's servers before it asks the model (the proof's run A). The stand-in only reads
// the request and refuses it, which ends the run. No network: a proxy that refuses every connection covers all but
// loopback, and nothing the user configured is read.
package splice.client.mcp

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import splice.core.wire.HttpStatus
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

private const val PLUGIN = "spliceflagcheck"
private const val SKILL = "hello"
private const val MARKER = "mcp-started"

// why: a print-mode run that is refused at its first request takes a few seconds; a minute is a hung binary.
private const val RUN_SECONDS = 60L
private const val REFUSING_PROXY = "http://127.0.0.1:9"

// A refusal the client does not retry (x-should-retry: false below), so one request ends the run.
private const val REFUSAL = """{"type":"error","error":{"type":"invalid_request_error",""" +
    """"message":"splice's plugin flag check reads one request and ends the run"}}"""

/** Runs the check against one installed version's binary and says what it found. */
public class PluginFlagCheck(private val clock: Clock = Clock.systemUTC()) {

    public fun run(binary: Path): PluginFlagVerdict {
        val root = Files.createTempDirectory("splice-plugin-flag-")
        return try {
            verdict(binary, root)
        } catch (error: IOException) {
            failed("the check could not run: ${error::class.simpleName}")
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun verdict(binary: Path, root: Path): PluginFlagVerdict {
        val plugin = plugin(root)
        val model = StandInModel()
        val exit = try {
            exitOf(binary, root, plugin, model.port)
        } finally {
            model.stop()
        }
        return when {
            exit == null -> failed("Claude Code gave no answer within ${RUN_SECONDS}s")
            Files.exists(root.resolve(MARKER)) -> failed("the plugin's .mcp.json was read: its server started")
            !model.asked.get() -> failed("Claude Code exited $exit without asking the model")
            !model.sawSkill.get() -> failed("the plugin's skill $PLUGIN:$SKILL did not reach the model")
            else -> PluginFlagVerdict(true, clock.instant().toString())
        }
    }

    private fun failed(why: String) = PluginFlagVerdict(false, clock.instant().toString(), why)

    /** The plugin, its skill, and its one server, which only leaves the marker. */
    private fun plugin(root: Path): Path {
        val plugin = root.resolve("plugin").resolve(PLUGIN)
        val manifest = Files.createDirectories(plugin.resolve(CLAUDE_PLUGIN_DIR))
        val skill = Files.createDirectories(plugin.resolve("skills").resolve(SKILL))
        Files.writeString(manifest.resolve("plugin.json"), """{"name":"$PLUGIN","version":"1.0.0"}""")
        Files.writeString(
            skill.resolve("SKILL.md"),
            "---\nname: $SKILL\ndescription: splice's plugin flag check. Says hello.\n---\nSay hello.\n",
        )
        val marker = root.resolve(MARKER)
        Files.writeString(
            plugin.resolve(".mcp.json"),
            """{"mcpServers":{"tools":{"command":"/bin/sh","args":["-c",": > '$marker'"]}}}""",
        )
        return plugin
    }

    /** The binary's exit code, or null when it ran past [RUN_SECONDS] and was stopped. */
    private fun exitOf(binary: Path, root: Path, plugin: Path, port: Int): Int? {
        val home = Files.createDirectories(root.resolve("home"))
        val work = Files.createDirectories(root.resolve("work"))
        val builder = ProcessBuilder(binary.toString(), "-p", "Say ready.", "--plugin-dir-no-mcp", plugin.toString())
            .directory(work.toFile())
            .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
        builder.environment().run {
            clear()
            putAll(environment(home, port))
        }
        val process = builder.start()
        return try {
            if (process.waitFor(RUN_SECONDS, TimeUnit.SECONDS)) process.exitValue() else null
        } finally {
            process.destroyForcibly()
        }
    }

    private fun environment(home: Path, port: Int): Map<String, String> = mapOf(
        "HOME" to home.toString(),
        "PATH" to "/usr/bin:/bin",
        "LANG" to "C.UTF-8",
        "TERM" to "dumb",
        "ANTHROPIC_BASE_URL" to "http://127.0.0.1:$port",
        "ANTHROPIC_AUTH_TOKEN" to "splice-plugin-flag-check",
        "DISABLE_AUTOUPDATER" to "1",
        "CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC" to "1",
        "DISABLE_TELEMETRY" to "1",
        "DISABLE_ERROR_REPORTING" to "1",
        "HTTPS_PROXY" to REFUSING_PROXY,
        "HTTP_PROXY" to REFUSING_PROXY,
        "NO_PROXY" to "127.0.0.1,localhost,::1",
    )
}

/** The run's model, on loopback for one run: it remembers whether a Messages request came and whether it named the
 *  plugin's skill, and refuses every request. */
private class StandInModel {
    val asked = AtomicBoolean()
    val sawSkill = AtomicBoolean()
    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
        createContext("/") { exchange -> answer(exchange) }
        start()
    }
    val port: Int get() = server.address.port

    fun stop() = server.stop(0)

    private fun answer(exchange: HttpExchange) = exchange.use {
        val body = it.requestBody.readAllBytes().decodeToString()
        if (it.requestURI.path.startsWith("/v1/messages")) {
            asked.set(true)
            if (body.contains("$PLUGIN:$SKILL")) sawSkill.set(true)
        }
        it.responseHeaders.add("content-type", "application/json")
        it.responseHeaders.add("x-should-retry", "false")
        val bytes = REFUSAL.encodeToByteArray()
        it.sendResponseHeaders(HttpStatus.BAD_REQUEST, bytes.size.toLong())
        it.responseBody.write(bytes)
    }
}
