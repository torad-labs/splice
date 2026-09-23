// NEW: V4-124 — the prompt layers through the REAL turn path. A request goes over HTTP into the same
// TurnPreparation a head runs, on the passthrough dialect, where each layer is a visible block of
// the `system` array.
//
// This pins the WIRE BYTES of a project layer. The oracle replay cannot pin them: its fixtures are
// recordings of the legacy Node stack, which never had projects. The oracle's eleven scenarios
// configure no projects table, so they carry the other half, never-below-status-quo, on every
// gate run.
package splice.head.turn

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpHeaders
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.perf.TurnPerf
import splice.core.prompt.HeadSystemPrompt
import splice.core.prompt.SystemPromptLayers
import splice.core.prompt.SystemPromptMode
import splice.core.topology.ProjectConfig
import splice.core.topology.ProjectHeadPrompt
import splice.core.turn.WatchdogBudget
import splice.dialect.anthropic.PassthroughProvider
import splice.dialect.anthropic.PassthroughQuirks
import splice.head.AnthropicBodyParse
import splice.head.ClientAuth
import splice.head.HeadDeps
import splice.head.RequestBodyReader
import splice.head.admission.AdmissionResponses
import splice.head.compaction.SessionProjectLookup
import splice.head.headDeps
import splice.upstream.BuiltTurn
import splice.upstream.ProviderTuning
import splice.upstream.retry.InflightGate
import splice.upstream.transport.UpstreamClient
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

private class LayersTestAuth : RefreshableAuthProvider {
    override suspend fun credentials(): Credentials = Credentials.Bearer("token", "account")
    override suspend fun refresh(): Credentials = credentials()
    override suspend fun describe(): AuthDescription = AuthDescription(true, "test")
}

class TurnPreparationTest {

    private val parser = AnthropicBodyParse()
    private val lookups = AtomicInteger()

    @Test
    fun `with no projects the bytes are exactly the head layer's and no session is looked up`(@TempDir tmp: Path) {
        val head = HeadSystemPrompt(text = "N", source = "head:kimi")
        val prepared = preparedTurn(tmp, SystemPromptLayers(head, headKey = "kimi"))
        val built = provider().buildTurn(parser.parse(REQUEST).getOrThrow(), compact = false, sessionId = SESSION)
        val direct = provider().withSystemPrompt(built, "N", SystemPromptMode.APPEND)

        assertEquals(direct.requestBody.toString(), prepared.requestBody.toString())
        assertEquals("head:kimi append", prepared.meta.systemPromptSource)
        assertEquals(0, lookups.get())
    }

    @Test
    fun `a session inside the project carries head then project then project-head as trailing blocks`(
        @TempDir tmp: Path,
    ) {
        val prepared = preparedTurn(tmp, layers(SystemPromptMode.APPEND))

        assertEquals(listOf("house rules", "N", "P", "H"), systemTexts(prepared))
        assertEquals("N\n\nP\n\nH", prepared.meta.systemPrompt)
        assertEquals(
            "head:kimi append+project:$ROOT append+project-head:$ROOT:kimi append",
            prepared.meta.systemPromptSource,
        )
        assertEquals(1, lookups.get())
    }

    @Test
    fun `a project replace drops the client field and the head layer and keeps the later append`(
        @TempDir tmp: Path,
    ) {
        val prepared = preparedTurn(tmp, layers(SystemPromptMode.REPLACE))

        assertEquals(listOf("P", "H"), systemTexts(prepared))
        assertEquals("project:$ROOT replace+project-head:$ROOT:kimi append", prepared.meta.systemPromptSource)
    }

    @Test
    fun `a session whose cwd cannot be resolved gets the head layer only`(@TempDir tmp: Path) {
        val prepared = preparedTurn(tmp, layers(SystemPromptMode.APPEND), cwd = null)

        assertEquals(listOf("house rules", "N"), systemTexts(prepared))
        assertEquals("head:kimi append", prepared.meta.systemPromptSource)
    }

    private fun layers(projectMode: SystemPromptMode) = SystemPromptLayers(
        head = HeadSystemPrompt(text = "N", source = "head:kimi"),
        projects = mapOf(
            ROOT to ProjectConfig(
                systemPrompt = "P",
                systemPromptMode = projectMode,
                heads = mapOf("kimi" to ProjectHeadPrompt(systemPrompt = "H")),
            ),
        ),
        headKey = "kimi",
    )

    private fun systemTexts(turn: BuiltTurn): List<String> = turn.requestBody.getValue("system").jsonArray
        .map { it.jsonObject.getValue("text").jsonPrimitive.content }

    private fun preparedTurn(tmp: Path, layers: SystemPromptLayers, cwd: Path? = Paths.get(ROOT, "src")): BuiltTurn {
        val deps = headDeps(
            tmp = tmp,
            upstream = UpstreamClient(firstByteTimeoutMs = 1_000, totalTimeoutMs = 1_000, maxRetries = 1),
            gate = InflightGate({ 1 }),
            policy = HeadDeps.HeadPolicy(systemPrompt = layers),
            seams = HeadDeps.HeadSeams(
                sessionProject = SessionProjectLookup { session ->
                    lookups.incrementAndGet()
                    if (session == SESSION) cwd else null
                },
            ),
        )
        val preparation = TurnPreparation(
            provider(),
            deps,
            RequestBodyReader(deps.policy.requestReadTimeoutMs),
            parser,
            ClientAuth(deps, AdmissionResponses()),
        )
        var captured: BuiltTurn? = null
        testApplication {
            application {
                routing {
                    post("/v1/messages") {
                        val result = preparation.prepareTurn(call, TurnPerf())
                        captured = (result as? Preparation.Ready)?.built ?: error("expected Ready, got $result")
                        call.respondText("ok")
                    }
                }
            }
            client.post("/v1/messages") {
                header(HttpHeaders.ContentType, "application/json")
                header("x-claude-code-session-id", SESSION)
                setBody(REQUEST)
            }
        }
        return requireNotNull(captured)
    }

    private fun provider() = PassthroughProvider(
        ProviderTuning(
            key = "kimi",
            label = "kimix",
            catalog = ModelCatalog(
                discoveryPrefix = "claude-kimi--",
                models = listOf(ModelEntry(MODEL, "Kimi", contextWindow = 200_000)),
                defaultContextWindow = 200_000,
            ),
            pinnedModel = MODEL,
            auth = LayersTestAuth(),
            baseUrl = "http://127.0.0.1",
            watchdog = WatchdogBudget(5.seconds, 3.seconds, 30.seconds),
        ),
        PassthroughQuirks(providerTag = "test-passthrough"),
    )
}

private const val ROOT = "/work/api"
private const val SESSION = "session-v4124"
private const val MODEL = "kimi-k2"

private const val REQUEST =
    """{"model":"claude-kimi--$MODEL","stream":false,"max_tokens":16,""" +
        """"system":[{"type":"text","text":"house rules"}],"messages":[{"role":"user","content":"hello"}]}"""
