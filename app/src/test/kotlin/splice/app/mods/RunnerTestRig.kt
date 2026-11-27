package splice.app.mods

import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import splice.app.control.ConsolePorts
import splice.app.control.ControlAuth
import splice.app.mods.install.InstalledMods
import splice.app.mods.run.ModRunner
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import splice.core.util.LogSink
import splice.upstream.Waiter
import splice.upstream.codemode.ProcessWaiter
import java.nio.file.Files
import java.nio.file.Path

private const val LOG_POLL_MS = 25L // why: bound the wait for a short local pipe round trip without spinning

internal class RunnerTestRig(
    val home: Path,
    env: Map<String, String> = System.getenv(),
    retryWait: Waiter = ProcessWaiter(),
    daemonLog: LogSink = LogSink {},
) {
    val paths = StatePaths(baseOverride = home.resolve("state"), envReader = { name ->
        if (name == "HOME") home.toString() else env[name]
    })
    private val key = MgmtKey(paths, LogSink {})
    val mods = InstalledMods(paths)
    val runner = ModRunner(paths, mods, daemonLog, waiter = retryWait)
    private val ports = ConsolePorts().apply { this.mods = this@RunnerTestRig.mods }
    val mount = ModsMount(ports, ControlAuth(key, LogSink {}).guard())

    fun fixture(name: String, register: String): Path {
        val source = Files.createDirectory(home.resolve(name))
        val manifest = Files.createDirectory(source.resolve(".splice-mod")).resolve("mod.json")
        Files.writeString(
            manifest,
            """
            {"name":"$name","version":"1.0.0","description":"Record what the mod receives.",
             "surfaces":["console"],"capabilities":["watch-sessions"]}
            """.trimIndent(),
        )
        val hooks = Files.createDirectory(source.resolve("hooks"))
        Files.writeString(hooks.resolve("hooks.json"), """{"modules":["./register.ts"]}""")
        Files.writeString(hooks.resolve("register.ts"), register)
        return source
    }

    suspend fun install(client: HttpClient, source: Path, linked: Boolean = false, base: String = ""): JsonObject {
        val response = client.post("$base/api/mods") {
            bearerAuth(key.get())
            setBody(
                buildJsonObject {
                    put("source", source.toString())
                    put("link", linked)
                    mods.bun.atInstall()?.let { put("bunPath", it.toString()) }
                }.toString(),
            )
        }
        val body = response.bodyAsText()
        assertEquals(HttpStatusCode.Created, response.status, body)
        return Json.parseToJsonElement(body).jsonObject
    }

    suspend fun disable(client: HttpClient, name: String): JsonObject {
        val response = client.put("/api/mods/$name") {
            bearerAuth(key.get())
            setBody("""{"enabled":false}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        return Json.parseToJsonElement(response.bodyAsText()).jsonObject
    }

    suspend fun rows(client: HttpClient): List<JsonObject> {
        val response = client.get("/api/mods") { bearerAuth(key.get()) }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        return body.getValue("mods").jsonArray.map { it.jsonObject }
    }

    fun log(name: String): String = paths.logsDir.resolve("mods/$name.log").let {
        if (Files.exists(it)) Files.readString(it) else ""
    }

    suspend fun <T : Any> eventually(read: suspend () -> T?): T = withTimeout(10_000) {
        var result = read()
        while (result == null) {
            delay(LOG_POLL_MS)
            result = read()
        }
        result
    }
}
