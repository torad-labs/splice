// NEW: LAN share, round 16 of PR #419's review. The goal: whatever a damaged or hand-edited tls.pem holds, the daemon
// starts and every local session carries on; sharing stays off, with the reason and its fix in words (move the file
// away to mint a new one); and `splice share status` and the Sharing page show the certificate's fingerprint while the
// file still holds the one minted, and none once it does not, never a stack trace or a 500. The listener says it is
// listening exactly when something answers on its port, through any run of damaged files and tables that move the port
// or turn sharing off. tls.pem is file bytes no list of examples covers, so both properties run over generated damage
// to a real minted file: random DER, a key of another kind, random bytes, blocks cut short or missing, and random text.
package splice.share

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.config.ConfigService
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import splice.core.testing.TestPorts
import splice.core.topology.ShareConfig
import splice.core.topology.Topology
import splice.core.util.EnvReader
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPairGenerator
import java.util.Base64
import kotlin.random.Random

// why: a fixed seed, so a failing draw is the same draw next time.
private const val DAMAGE_SEED = 16

// why: enough draws that each kind of damage lands several times, cut at the start, the middle and the end.
private const val DAMAGE_RUNS = 48

// why: steps in a run of files and tables: enough that the port moves, sharing turns off and on, and a damaged file
// meets an open listener and a closed one, each several times. Each reopening waits out the server's 1 s grace.
private const val FOLLOW_STEPS = 20

// why: a connect to loopback answers at once; a second covers a loaded machine.
private const val CONNECT_MS = 1_000

// why: an RSA key of the size a person would have lying around; one is made per test.
private const val RSA_BITS = 2_048

// why: random DER and random bytes up to the size of a real certificate.
private const val RANDOM_AT_MOST = 600

// why: pieces of a hand-edited file: PEM's markers, base64 a DER sequence starts with, and what PEM never holds.
private val TEXT_PIECES = listOf(
    "-----BEGIN CERTIFICATE-----", "-----END CERTIFICATE-----", "-----BEGIN PRIVATE KEY-----",
    "-----END PRIVATE KEY-----", "MII", "MIIB", "AAAA", "\n", "=", "x", "é", " ",
)

/** A way tls.pem gets damaged, and whether the certificate in it is still the one minted. */
private data class Damage(val name: String, val keepsCertificate: Boolean, val apply: (String, Random) -> ByteArray)

class ShareCertificateDamageTest {
    @TempDir
    lateinit var tmp: Path

    private val paths by lazy {
        val env = mapOf(
            "SPLICE_STATE_DIR" to tmp.resolve("state").toString(),
            "SPLICE_CONFIG" to tmp.resolve("splice.toml").toString(),
            "HOME" to tmp.toString(),
        )
        StatePaths(envReader = EnvReader { env[it] })
    }

    private val listener by lazy {
        ShareListener(
            ShareStart(Topology(), paths, MgmtKey(paths), ConfigService(paths)) { },
            SharedCatalogs { null },
            SharedQuotas { null },
            paths,
            MachinesJoined { emptySet() },
            { },
        )
    }

    private val file by lazy { ShareState(paths).certificateFile }

    private val rsaKey by lazy {
        KeyPairGenerator.getInstance("RSA").apply { initialize(RSA_BITS) }.generateKeyPair().private.encoded
    }

    private val damages = listOf(
        Damage("random DER in the certificate", keepsCertificate = false) { text, random ->
            text.withBody("CERTIFICATE", base64(random.nextBytes(random.nextInt(1, RANDOM_AT_MOST)))).ascii()
        },
        Damage("an RSA key", keepsCertificate = true) { text, _ ->
            text.withBody("PRIVATE KEY", base64(rsaKey)).ascii()
        },
        Damage("random key bytes", keepsCertificate = true) { text, random ->
            text.withBody("PRIVATE KEY", base64(random.nextBytes(random.nextInt(1, RANDOM_AT_MOST)))).ascii()
        },
        Damage("the certificate cut short", keepsCertificate = false) { text, random ->
            text.withBody("CERTIFICATE", text.body("CERTIFICATE").cut(random)).ascii()
        },
        Damage("the key cut short", keepsCertificate = true) { text, random ->
            text.withBody("PRIVATE KEY", text.body("PRIVATE KEY").cut(random)).ascii()
        },
        Damage("no certificate", keepsCertificate = false) { text, _ -> text.without("CERTIFICATE").ascii() },
        Damage("no key", keepsCertificate = true) { text, _ -> text.without("PRIVATE KEY").ascii() },
        Damage("random text", keepsCertificate = false) { _, random ->
            (0 until random.nextInt(0, 40)).joinToString("") { TEXT_PIECES[random.nextInt(TEXT_PIECES.size)] }
                .toByteArray()
        },
        Damage("random bytes", keepsCertificate = false) { _, random ->
            random.nextBytes(random.nextInt(1, RANDOM_AT_MOST))
        },
    )

    @AfterEach
    fun stop() {
        runBlocking { listener.stop() }
    }

    /** A real certificate, minted as a first start mints it: the file's text, and the fingerprint a client pins. */
    private fun minted(): Pair<String, String> {
        val tls = ShareCertificate(file).load(listOf("splice"))
        return Files.readString(file) to tls.fingerprint
    }

    private fun answers(port: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), CONNECT_MS) }
        true
    } catch (_: IOException) {
        false
    }

    @Test
    fun `any damaged tls_pem leaves sharing off, says to move it away, and shows the fingerprint it holds`() {
        val (good, pinned) = minted()
        val port = TestPorts.reserve()
        val random = Random(DAMAGE_SEED)
        repeat(DAMAGE_RUNS) { run ->
            val damage = damages[random.nextInt(damages.size)]
            Files.write(file, damage.apply(good, random))
            val at = "run $run, ${damage.name}"

            val shown = ShareCertificate(file).fingerprint()
            val state = runBlocking { listener.follow(ShareConfig(enabled = true, port = port)) }

            assertEquals(if (damage.keepsCertificate) pinned else null, shown, "$at: the fingerprint it holds")
            assertFalse(state.listening, "$at: nothing is served with a damaged file")
            assertFalse(answers(port), "$at: and nothing answers on its port")
            val why = state.why.orEmpty()
            assertTrue("move it away" in why, "$at: it says to move the file away: $why")
        }
    }

    @Test
    fun `through any run of damaged files and tables, the listener says listening exactly when its port answers`() {
        val (good, _) = minted()
        val a = TestPorts.reserve()
        val b = TestPorts.reserve()
        val tables = listOf(
            ShareConfig(enabled = true, port = a),
            ShareConfig(enabled = true, port = b),
            ShareConfig(enabled = false, port = a),
        )
        val random = Random(DAMAGE_SEED)
        repeat(FOLLOW_STEPS) { step ->
            val damage = damages[random.nextInt(damages.size)].takeIf { random.nextInt(3) == 0 }
            Files.write(file, damage?.apply(good, random) ?: good.ascii())
            val table = tables[random.nextInt(tables.size)]
            val at = "step $step, ${damage?.name ?: "a good file"}, $table"

            val state = runBlocking { listener.follow(table) }

            assertEquals(state, listener.state(), "$at: the page reads what the follow answered")
            assertEquals(answers(table.port), state.listening, "$at: listening is what the port does: $state")
            val other = if (table.port == a) b else a
            assertFalse(answers(other), "$at: nothing answers on the port it does not follow")
        }
    }
}

private fun base64(der: ByteArray): String =
    "\n" + Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(der) + "\n"

private fun String.ascii(): ByteArray = toByteArray(Charsets.US_ASCII)

private fun String.body(type: String): String =
    substringAfter("-----BEGIN $type-----").substringBefore("-----END $type-----")

private fun String.withBody(type: String, body: String): String = replace(body(type), body)

private fun String.without(type: String): String =
    replace("-----BEGIN $type-----${body(type)}-----END $type-----\n", "")

/** This base64 body cut off somewhere inside, so the DER it decodes to ends early. */
private fun String.cut(random: Random): String = "\n" + trim().take(random.nextInt(1, trim().length)) + "\n"
