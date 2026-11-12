// Fix 3's property, over generated histories: a head's model list is the one the backend gave the credential it was
// last asked with. Its sign-in is read again on every recheck and when a turn arrives, so a new credential (a sign-in,
// another account's, a sign-out) is asked at the next recheck or before the next turn, whichever comes first, never
// only at the hourly refresh; an unchanged credential is not asked again; and a list that cannot be fetched leaves the
// last one standing, as the hourly refresh always has. Concurrent rounds and a sign-in landing between them converge
// on the new account's list at the next recheck or turn.
package splice.app.roster

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import splice.app.auth.EndpointModels
import splice.app.provider.ModelRosters
import splice.core.config.StatePaths
import splice.core.topology.AuthConfig
import splice.core.topology.AuthKind
import splice.core.topology.Dialect
import splice.core.topology.ProviderConfig
import splice.core.util.EnvReader
import java.nio.file.Path
import kotlin.random.Random

// A history asks the backend over HTTP for most of its steps, at about 30 ms a step on this machine.
private const val RUNS = 20
private const val STEPS = 25
private const val SEED = 20_261_027L

/** Each account the backend lists models for, and the models it lists. */
private val LISTED = mapOf(
    "synthetic-account-a" to listOf("gpt-6-a"),
    "synthetic-account-b" to listOf("gpt-6-b1", "gpt-6-b2"),
    "synthetic-account-c" to listOf("gpt-6-c"),
)

/** An account whose sign-in the backend refuses to list for. */
private const val REFUSED = "synthetic-account-refused"

@Timeout(180)
class SignInRosterPropertyTest {
    @Test
    fun `a head's list follows its sign-in at the next recheck, over generated histories`(@TempDir tmp: Path) =
        runBlocking {
            ChatGptListBackend().use { backend ->
                repeat(RUNS) { run -> History(tmp.resolve("run-$run"), backend, run).play() }
            }
        }
}

/** One generated history against a fresh daemon state, checked against a model of what the list must be. */
private class History(dir: Path, private val backend: ChatGptListBackend, private val run: Int) {
    private val random = Random(SEED + run)
    private val login = SyntheticChatGptLogin(dir.resolve("auth.json"))
    private val heads = mapOf(
        CODEX_HEAD to ProviderConfig(
            Dialect.OPENAI_RESPONSES,
            backend.baseUrl,
            AuthConfig(AuthKind.ChatgptOAuth.wire, file = dir.resolve("auth.json").toString()),
        ),
    )
    private val rosters =
        ModelRosters(StatePaths(baseOverride = dir.resolve("state")), {}, EndpointModels(EnvReader { null }))
    private val steps = mutableListOf<String>()

    // The model: the token on disk and whose it is, the token the list was last asked with, and the list it must hold.
    private var onDisk: String? = null
    private var account: String? = null
    private var askedWith: String? = null
    private var outage = false
    private var expected: List<String> = emptyList()

    suspend fun play() {
        backend.listOutage = false
        if (random.nextBoolean()) signIn(pick(), "start")
        steps += "daemon start"
        rosters.resolve(heads)
        asked()
        check()
        repeat(STEPS) { step ->
            act(step)
            check()
        }
    }

    private suspend fun act(step: Int) {
        val roll = random.nextInt(100)
        when {
            roll < 25 -> signIn(if (random.nextInt(5) == 0) REFUSED else pick(), "$step")
            roll < 35 -> signOut()
            roll < 45 -> toggleOutage()
            roll < 60 -> recheck()
            roll < 72 -> turn()
            roll < 85 -> hourly()
            else -> concurrent(step)
        }
    }

    private fun pick(): String = LISTED.keys.random(random)

    private fun signIn(who: String, nonce: String) {
        val token = login.accessToken(who, "$run-$nonce")
        LISTED[who]?.let { models -> backend.serve(token, models.map { listedModel(it, codeModeOnly = false) }) }
        login.signIn(who, "$run-$nonce")
        steps += "$who signs in"
        onDisk = token
        account = who
    }

    private fun signOut() {
        login.signOut()
        steps += "sign-out"
        onDisk = null
        account = null
    }

    private fun toggleOutage() {
        outage = !outage
        backend.listOutage = outage
        steps += if (outage) "list outage starts" else "list outage ends"
    }

    private suspend fun recheck() {
        steps += "recheck"
        rosters.recheck(heads)
        if (onDisk != askedWith) asked()
    }

    /** A turn arrives on the head with no recheck beat since the last step: what it waits for before it is admitted. */
    private suspend fun turn() {
        steps += "a turn arrives"
        rosters.awaitCurrent(CODEX_HEAD)
        if (onDisk != askedWith) asked()
    }

    private suspend fun hourly() {
        steps += "hourly refresh"
        rosters.resolve(heads)
        asked()
    }

    /** An hourly refresh, a recheck and a turn racing a sign-in, then one recheck or one turn: whatever order they ran
     *  in, the list is the new account's. */
    private suspend fun concurrent(step: Int) {
        outage = false
        backend.listOutage = false
        val who = pick()
        val token = login.accessToken(who, "$run-$step")
        backend.serve(token, LISTED.getValue(who).map { listedModel(it, codeModeOnly = false) })
        coroutineScope {
            launch { rosters.resolve(heads) }
            launch { rosters.recheck(heads) }
            launch { rosters.awaitCurrent(CODEX_HEAD) }
            launch {
                repeat(random.nextInt(3)) { yield() }
                login.signIn(who, "$run-$step")
            }
        }
        val settle = if (random.nextBoolean()) "recheck" else "turn"
        if (settle == "turn") rosters.awaitCurrent(CODEX_HEAD) else rosters.recheck(heads)
        steps += "list outage ends; hourly refresh, recheck and turn race $who's sign-in; $settle"
        onDisk = token
        account = who
        askedWith = token
        expected = LISTED.getValue(who)
    }

    /** The list was asked with the token on disk: it is that account's when the backend answers, else unchanged. */
    private fun asked() {
        askedWith = onDisk
        val listed = account?.let(LISTED::get)
        if (!outage && listed != null) expected = listed
    }

    private fun check() {
        val held = rosters.forHead(CODEX_HEAD).map { it.id }
        assertEquals(expected, held, "run $run (seed ${SEED + run}): ${steps.joinToString(" -> ")}")
    }
}
