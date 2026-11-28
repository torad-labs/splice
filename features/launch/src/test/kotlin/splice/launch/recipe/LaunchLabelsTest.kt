package splice.launch.recipe

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.accept
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.client.ClaudeConfigMaterializer
import splice.client.wrap.ClaudeToRun
import splice.client.wrap.WrapStateRead
import splice.http.JsonBody
import splice.launch.LaunchAudit
import splice.launch.launchHeadsOf
import java.nio.file.Path

class LaunchLabelsTest(@param:TempDir private val home: Path) {
    @Test
    fun `the launch carries its own owner and names each rejected label without its value`() = runTest {
        val service = LaunchService(
            ClaudeConfigMaterializer(home),
            wrapState = WrapStateRead { ClaudeToRun.ThroughPath },
        ).apply {
            door = object : ClaudeDoor {
                override suspend fun launch() = DoorLaunchSpec(emptyMap(), emptyList(), "{}", 200_000)
                override fun onDoor() = true
            }
        }
        val audit = object : LaunchAudit {
            override fun launched(key: String, argv: List<String>) = Unit
            override fun warned(message: String) = Unit
            override fun refused(key: String, reason: String) = Unit
        }
        val route = LaunchRoutes(launchHeadsOf(), service, audit, JsonBody())
        val server = embeddedServer(Netty, host = "127.0.0.1", port = 0) {
            routing { post("/launch/{head}") { route.launch(call) } }
        }.start()
        try {
            val port = server.engine.resolvedConnectors().single().port
            val recipe = HttpClient(CIO).use { client ->
                val response = client.submitForm("http://127.0.0.1:$port/launch/claude", labelForm()) {
                    accept(ContentType.parse(LaunchRecipeLines.RECIPE_MEDIA_TYPE))
                }
                assertEquals(HttpStatusCode.OK, response.status)
                response.bodyAsText()
            }
            val owner = recipe.lineSequence().single { it.startsWith("E SPLICE_FOREGROUND_OWNER=") }
                .substringAfter("E SPLICE_FOREGROUND_OWNER=")
            val headerLine = recipe.lineSequence().single { it.startsWith("E ANTHROPIC_CUSTOM_HEADERS=") }
            assertEquals("E ANTHROPIC_CUSTOM_HEADERS=x-splice-launch-owner: $owner", headerLine)
            listOf("TOO_MANY", "WITH_TAB", "TOO_LONG", "1BAD", "badCase").forEach { key ->
                assertEquals(
                    1,
                    recipe.lineSequence().filter { it.startsWith("W ") }
                        .sumOf { line -> Regex("SPLICE_LABEL_$key\\b").findAll(line).count() },
                    recipe,
                )
            }
            assertTrue("missing-mod" in recipe, recipe)
            assertTrue("do not print" !in recipe && "ENG-412" !in recipe, recipe)
        } finally {
            server.stop()
        }
    }

    private fun labelForm(): Parameters = Parameters.build {
        append("label", "TICKET=ENG-412")
        append("label", "EMPTY=")
        append("label", "FULL=${"é".repeat(128)}")
        repeat(13) { append("label", "TAG_$it=value") }
        append("label", "TOO_MANY=value")
        append("label", "WITH_TAB=do\tnot print")
        append("label", "TOO_LONG=${"é".repeat(129)}")
        append("label", "1BAD=do not print")
        append("label", "badCase=do not print")
        append("modsOff", "missing-mod")
    }
}
