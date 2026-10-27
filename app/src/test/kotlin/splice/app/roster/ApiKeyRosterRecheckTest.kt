// Fix 3 (Oct 27), the count's second gap: a new credential for a head gets that credential's model list without waiting
// for the hourly refresh, and an api-key head's credential is the key its list is asked with, read from the daemon's
// environment and then from splice's key store, which the console's key route writes while the daemon runs. These drive
// the console's real key route (a control server over a key store in a temp folder) and the daemon's real roster, which
// asks a loopback list backend with the key it reads, with both roster beats held by the test.
package splice.app.roster

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import splice.app.control.ControlAuth
import splice.app.control.controlServerFor
import splice.app.provider.EndpointModels
import splice.app.provider.ModelRosters
import splice.core.config.ConfigService
import splice.core.config.KeyStore
import splice.core.config.KeyStorePath
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import splice.core.model.ModelEntry
import splice.core.topology.AuthConfig
import splice.core.topology.Dialect
import splice.core.topology.ProviderConfig
import splice.core.util.EnvReader
import splice.core.util.LogSink
import splice.core.wire.HttpStatus
import splice.upstream.LifecycleScope
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds

private const val API_KEY_HEAD = "synthetic"
private const val KEY_ENV = "SYNTHETIC_API_KEY"
private const val KEY_A = "sk-synthetic-key-a"
private const val KEY_B = "sk-synthetic-key-b"
private const val MODEL_A = "synthetic-model-a"
private const val MODEL_B = "synthetic-model-b"

@Timeout(90)
class ApiKeyRosterRecheckTest {
    @Test
    fun `a key set in the console reaches its api-key head's list at the next recheck, with no hourly refresh`(
        @TempDir tmp: Path,
    ) = runBlocking {
        val fixture = ApiKeyRosterFixture(tmp, this)
        try {
            withTimeout(60.seconds) {
                fixture.backend.serve(KEY_A, listOf(listedModel(MODEL_A, codeModeOnly = false)))
                fixture.backend.serve(KEY_B, listOf(listedModel(MODEL_B, codeModeOnly = false)))
                fixture.start()
                assertEquals(emptyList<String>(), fixture.listed(), "no key is set yet, so no list is known")

                val first = fixture.setKey(KEY_A)
                assertEquals(HttpStatus.OK, first.statusCode(), first.body())
                fixture.credentialBeat.beat()
                assertEquals(listOf(MODEL_A), fixture.listed(), "the key set in the console reached the head's list")

                val second = fixture.setKey(KEY_B)
                assertEquals(HttpStatus.OK, second.statusCode(), second.body())
                fixture.credentialBeat.beat()
                assertEquals(listOf(MODEL_B), fixture.listed(), "the key that replaced it replaced the head's list")
            }
        } finally {
            fixture.close()
        }
    }

    /** The recheck reads the key store every beat, so a store that cannot be read must still be said once per
     *  version of it, as the store says it on every other path, and the list's own fallback line once. */
    @Test
    fun `a key store that cannot be read is said once, however many rechecks read it`(@TempDir tmp: Path) =
        runBlocking {
            val fixture = ApiKeyRosterFixture(tmp, this)
            try {
                withTimeout(60.seconds) {
                    Files.createDirectories(fixture.keysPath)
                    fixture.start()
                    repeat(3) { fixture.credentialBeat.beat() }
                    fixture.hourly.beat()

                    val unreadable = fixture.daemonLog.filter { it.startsWith("[keys] ") && "UNREADABLE" in it }
                    assertEquals(1, unreadable.size, "${fixture.daemonLog}")
                    assertEquals(
                        1,
                        fixture.daemonLog.count { it.startsWith("[$API_KEY_HEAD] models: ") },
                        "${fixture.daemonLog}",
                    )
                }
            } finally {
                fixture.close()
            }
        }
}

/** An api-key head's model list as the daemon keeps it, asked of a loopback backend with the key the daemon reads, and
 *  the console's control server writing splice's key store in the same temp folder. */
private class ApiKeyRosterFixture(tmp: Path, parent: CoroutineScope) {
    private val scope = LifecycleScope(parent.coroutineContext)
    private val paths = StatePaths(baseOverride = tmp.resolve("state"))
    private val configFile = tmp.resolve("config").resolve("splice").resolve("splice.toml")

    /** The daemon's environment: it names this folder's splice.toml and sets no key, so the key store beside that
     *  file is the one the daemon reads (KeyStorePath.defaultPath). */
    private val env = EnvReader { name -> if (name == "SPLICE_CONFIG") configFile.toString() else null }
    val keysPath: Path = KeyStorePath.defaultPath(env)
    val backend = ChatGptListBackend()

    /** Every line the daemon would write to its log, in order. */
    val daemonLog = CopyOnWriteArrayList<String>()
    private val log = LogSink { daemonLog += it }
    val hourly = HeldBeat()
    val credentialBeat = HeldBeat()

    private val provider = ProviderConfig(
        dialect = Dialect.OPENAI_CHAT,
        baseUrl = backend.baseUrl,
        auth = AuthConfig("api-key", env = KEY_ENV),
        models = listOf(ModelEntry("synthetic-declared", contextWindow = 128_000)),
        local = false,
    )
    private val heads = mapOf(API_KEY_HEAD to provider)
    private val rosters = ModelRosters(
        paths,
        log,
        EndpointModels(env, log),
        ticker = hourly,
        credentialTicker = credentialBeat,
    )
    private val mgmt = MgmtKey(paths)
    private val control =
        controlServerFor(0, emptyMap(), ConfigService(paths, envReader = { null }), ControlAuth(mgmt, log))
    private val client = HttpClient.newHttpClient()

    /** What the daemon does at its start: ask every head's list, open the console, then start both roster loops. The
     *  console's key store is the one the daemon's own heads read, as ConsolePortsWiring wires it. */
    suspend fun start() {
        rosters.resolve(heads)
        control.ports.keys = KeyStore(keysPath, log)
        control.start()
        rosters.start(scope, heads)
        hourly.waiting()
        credentialBeat.waiting()
    }

    /** The ids of the models the head's list holds now. */
    fun listed(): List<String> = rosters.forHead(API_KEY_HEAD).map { it.id }

    /** The console's PUT /api/keys/{name}, as its Accounts page sends it. */
    fun setKey(value: String): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:${control.listeningPort}/api/keys/$KEY_ENV"))
            .timeout(java.time.Duration.ofSeconds(15))
            .header("Authorization", "Bearer ${mgmt.get()}")
            .header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString("""{"value":"$value"}"""))
            .build()
        return client.send(request, HttpResponse.BodyHandlers.ofString())
    }

    suspend fun close() {
        scope.coroutineContext.job.cancelAndJoin()
        control.stop()
        backend.close()
    }
}
