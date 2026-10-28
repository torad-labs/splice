// Fix 3 (Oct 27), the count's second gap: a new credential for a head gets that credential's model list without waiting
// for the hourly refresh, and an api-key head's credential is the key its list is asked with, read as the head reads it:
// the daemon's environment, then the key file the head's auth names, then splice's key store, which the console's key
// route writes while the daemon runs. The third count (Oct 27) found the key file left out, so a key kept there never
// reached the list. These drive the console's real key route (a control server over a key store in a temp folder) and
// the daemon's real roster, which asks a loopback list backend with the key it reads, with both roster beats held by the
// test. `splice models` reads the key the same way (StoredCredential), and the last test drives that command.
package splice.app.roster

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import splice.app.auth.EndpointModels
import splice.app.auth.StoredModelCredentials
import splice.app.control.ControlAuth
import splice.app.control.controlServerFor
import splice.app.provider.ModelRosters
import splice.core.config.ConfigService
import splice.core.config.KeyStore
import splice.core.config.KeyStorePath
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import splice.core.model.ModelEntry
import splice.core.terminal.TerminalOutput
import splice.core.topology.AuthConfig
import splice.core.topology.Dialect
import splice.core.topology.ProviderConfig
import splice.core.util.EnvReader
import splice.core.util.LogSink
import splice.core.wire.HttpStatus
import splice.models.list.ModelConfiguration
import splice.models.list.ModelConfigurationSource
import splice.models.list.ModelsCommand
import splice.oauth.StoredCredential
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

/** One key per place an api-key head can keep it, each listed with a model of its own. */
private const val ENV_KEY = "sk-synthetic-environment-key"
private const val FILE_KEY = "sk-synthetic-file-key"
private const val STORE_KEY = "sk-synthetic-store-key"
private const val ENV_MODEL = "synthetic-environment-model"
private const val FILE_MODEL = "synthetic-file-model"
private const val STORE_MODEL = "synthetic-store-model"

@Timeout(90)
class ApiKeyRosterRecheckTest {
    @ParameterizedTest
    @ValueSource(strings = ["api-key", "custom-key"])
    fun `a key set in the console reaches its api-key head's list at the next recheck, with no hourly refresh`(
        authKind: String,
        @TempDir tmp: Path,
    ) = runBlocking {
        val fixture = ApiKeyRosterFixture(tmp, this, authKind = authKind)
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

    /** The head reads its environment, then the key file its auth names, then the key store (ApiKeyAuthProvider), so a
     *  key written to that file is the head's credential, and its list follows it at the next recheck. */
    @ParameterizedTest
    @ValueSource(strings = ["api-key", "custom-key"])
    fun `a key written to its api-key head's key file reaches the head's list at the next recheck`(
        authKind: String,
        @TempDir tmp: Path,
    ) =
        runBlocking {
            val fixture = ApiKeyRosterFixture(tmp, this, keyFile = tmp.resolve("synthetic.key"), authKind = authKind)
            try {
                withTimeout(60.seconds) {
                    fixture.backend.serve(KEY_A, listOf(listedModel(MODEL_A, codeModeOnly = false)))
                    fixture.backend.serve(KEY_B, listOf(listedModel(MODEL_B, codeModeOnly = false)))
                    fixture.start()
                    assertEquals(emptyList<String>(), fixture.listed(), "the key file holds no key yet, so no list")

                    fixture.writeKeyFile("$KEY_A\n")
                    fixture.credentialBeat.beat()
                    assertEquals(listOf(MODEL_A), fixture.listed(), "the key written to the key file reached the list")

                    fixture.writeKeyFile("""{"api_key":"$KEY_B"}""")
                    fixture.credentialBeat.beat()
                    assertEquals(
                        listOf(MODEL_B),
                        fixture.listed(),
                        "the key that replaced it, as JSON, replaced the list",
                    )
                }
            } finally {
                fixture.close()
            }
        }

    /** Every combination of where the key is kept, each a daemon start in a folder of its own: the list is asked with the
     *  environment's key, else the key the key file supplies, else the key store's, as the head presents them. A key file
     *  that supplies none is passed over for the store, as the head passes it over. */
    @ParameterizedTest
    @ValueSource(strings = ["api-key", "custom-key"])
    fun `an api-key head's list is asked with the key the head presents, in the head's own order`(
        authKind: String,
        @TempDir tmp: Path,
    ) =
        runBlocking {
            ChatGptListBackend().use { backend ->
                backend.serve(ENV_KEY, listOf(listedModel(ENV_MODEL, codeModeOnly = false)))
                backend.serve(FILE_KEY, listOf(listedModel(FILE_MODEL, codeModeOnly = false)))
                backend.serve(STORE_KEY, listOf(listedModel(STORE_MODEL, codeModeOnly = false)))
                val modelOf = mapOf(ENV_KEY to ENV_MODEL, FILE_KEY to FILE_MODEL, STORE_KEY to STORE_MODEL)
                val combinations = listOf(null, ENV_KEY).flatMap { environment ->
                    KeyFileHolds.entries.flatMap { file ->
                        listOf(null, STORE_KEY).map { stored -> KeySources(environment, file, stored) }
                    }
                }
                combinations.forEachIndexed { n, sources ->
                    val listed = listedAtStart(tmp.resolve("start-$n"), backend, sources, authKind)
                    assertEquals(listOfNotNull(sources.presented?.let(modelOf::getValue)), listed, "$sources")
                }
            }
        }

    /** `splice models` reads an api-key head's key through the same StoredCredential, so it asks with the key in the
     *  head's key file and reports what that key is served. */
    @ParameterizedTest
    @ValueSource(strings = ["api-key", "custom-key"])
    fun `splice models asks an api-key head's provider with the key in its key file`(
        authKind: String,
        @TempDir tmp: Path,
    ) {
        ChatGptListBackend().use { backend ->
            backend.serve(FILE_KEY, listOf(listedModel(FILE_MODEL, codeModeOnly = false)))
            val keyFile = tmp.resolve("synthetic.key")
            Files.writeString(keyFile, "$FILE_KEY\n")
            val configFile = tmp.resolve("splice.toml")
            val env = EnvReader { name -> if (name == "SPLICE_CONFIG") configFile.toString() else null }
            val provider = apiKeyProvider(backend.baseUrl, keyFile, declared = FILE_MODEL, authKind = authKind)
            val printed = mutableListOf<String>()
            val command = ModelsCommand(
                ModelConfigurationSource { ModelConfiguration(configFile.toString(), mapOf(API_KEY_HEAD to provider)) },
                StoredModelCredentials(StoredCredential(log = LogSink { })),
                TerminalOutput { printed.add(it) },
            )

            val agrees = command.models(listOf(API_KEY_HEAD), env)

            val report = printed.joinToString("\n")
            assertTrue(agrees, report)
            assertTrue(printed.none { "wants a credential" in it }, report)
            assertTrue(printed.any { FILE_MODEL in it }, report)
        }
    }
}

/** An api-key head on a loopback list backend, its key named by [KEY_ENV] and, when [keyFile] is set, by its auth's key
 *  file, with one declared row. */
private fun apiKeyProvider(
    baseUrl: String,
    keyFile: Path?,
    declared: String = "synthetic-declared",
    authKind: String = "api-key",
) = ProviderConfig(
    dialect = Dialect.OPENAI_CHAT,
    baseUrl = baseUrl,
    auth = AuthConfig(authKind, file = keyFile?.toString(), env = KEY_ENV),
    models = listOf(ModelEntry(declared, contextWindow = 128_000)),
    local = false,
)

/** What an api-key head's key file holds, and whether the head takes a key from it (ApiKeyFile.key). */
private enum class KeyFileHolds(val supplies: Boolean) {
    NO_FILE(false),
    BARE_LINE(true),
    JSON(true),
    EMPTY(false),
    JSON_WITHOUT_API_KEY(false),
    MALFORMED_JSON(false),
    UNREADABLE(false),
    ;

    fun writeAt(path: Path) {
        when (this) {
            NO_FILE -> Unit
            BARE_LINE -> Files.writeString(path, "$FILE_KEY\n")
            JSON -> Files.writeString(path, """{"api_key":"$FILE_KEY"}""")
            EMPTY -> Files.writeString(path, "")
            JSON_WITHOUT_API_KEY -> Files.writeString(path, """{"organization":"synthetic-org"}""")
            MALFORMED_JSON -> Files.writeString(path, "{")
            // A directory where the file should be: the read fails, as it does for a file the daemon may not read.
            UNREADABLE -> Files.createDirectories(path)
        }
    }
}

/** Where one start keeps the head's key: the daemon's environment, the head's key file and splice's key store. */
private data class KeySources(val environment: String?, val file: KeyFileHolds, val stored: String?) {
    /** The key the head presents (ApiKeyAuthProvider): the environment's, else its key file's, else the store's. */
    val presented: String? get() = environment ?: FILE_KEY.takeIf { file.supplies } ?: stored

    override fun toString(): String =
        "environment ${if (environment == null) "unset" else "set"}, key file $file, " +
            "key store ${if (stored == null) "empty" else "set"}"
}

/** One daemon start in [folder] with [sources] in place: the head's list as the start's ask leaves it. */
private suspend fun listedAtStart(
    folder: Path,
    backend: ChatGptListBackend,
    sources: KeySources,
    authKind: String,
): List<String> {
    Files.createDirectories(folder)
    val configFile = folder.resolve("splice.toml")
    val env = EnvReader { name ->
        when (name) {
            "SPLICE_CONFIG" -> configFile.toString()
            KEY_ENV -> sources.environment
            else -> null
        }
    }
    val log = LogSink { }
    sources.stored?.let { KeyStore(KeyStorePath.defaultPath(env), log).write(KEY_ENV, it) }
    val keyFile = folder.resolve("synthetic.key")
    sources.file.writeAt(keyFile)
    val rosters = ModelRosters(StatePaths(baseOverride = folder.resolve("state")), log, EndpointModels(env, log))
    rosters.resolve(mapOf(API_KEY_HEAD to apiKeyProvider(backend.baseUrl, keyFile, authKind = authKind)))
    return rosters.forHead(API_KEY_HEAD).map { it.id }
}

/** An api-key head's model list as the daemon keeps it, asked of a loopback backend with the key the daemon reads, and
 *  the console's control server writing splice's key store in the same temp folder. [keyFile], when set, is the key
 *  file the head's auth names. */
private class ApiKeyRosterFixture(
    tmp: Path,
    parent: CoroutineScope,
    private val keyFile: Path? = null,
    authKind: String = "api-key",
) {
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

    private val heads = mapOf(API_KEY_HEAD to apiKeyProvider(backend.baseUrl, keyFile, authKind = authKind))
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

    /** The head's key file, written whole as a person or a script writes it. */
    fun writeKeyFile(text: String) {
        Files.writeString(requireNotNull(keyFile) { "this head's auth names no key file" }, text)
    }

    suspend fun close() {
        scope.coroutineContext.job.cancelAndJoin()
        control.stop()
        backend.close()
    }
}
