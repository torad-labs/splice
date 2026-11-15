// Server mode's listener over TLS (docs/specs/server-mode.md, F1 `tls_cert` and `tls_key`), as an operator sets it up:
// the certificate and its key as two PEM files beside splice.toml, and a client that trusts that certificate.
package splice.server

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import splice.core.config.TurnKey
import splice.core.testing.TestPorts
import splice.core.topology.ServerAddress
import splice.core.topology.ServerConfig
import splice.core.util.LogSink
import splice.share.SharedHead
import splice.share.SharedHeads
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.security.cert.Certificate
import java.time.Duration
import java.util.Base64
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

internal class ServerTlsTest {
    @TempDir lateinit var dir: Path

    private val port = TestPorts.reserve()
    private var mode: ServerMode? = null

    @AfterEach
    fun stop() {
        runBlocking { mode?.close() }
    }

    /** A self-signed certificate for 127.0.0.1 and its key, made by the JDK's own keytool and read back. */
    private fun identity(): KeyStore.PrivateKeyEntry {
        val store = dir.resolve("identity.p12")
        val made = ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
            "-genkeypair", "-alias", ALIAS, "-keyalg", "RSA", "-keysize", "2048", "-validity", "2",
            "-dname", "CN=127.0.0.1", "-ext", "SAN=IP:127.0.0.1",
            "-storetype", "PKCS12", "-keystore", store.toString(), "-storepass", STORE_WORD,
        ).redirectErrorStream(true).start()
        val said = made.inputStream.readBytes().decodeToString()
        check(made.waitFor() == 0) { "keytool made no certificate: $said" }
        val loaded = KeyStore.getInstance("PKCS12")
        Files.newInputStream(store).use { loaded.load(it, STORE_WORD.toCharArray()) }
        val entry = loaded.getEntry(ALIAS, KeyStore.PasswordProtection(STORE_WORD.toCharArray()))
        return assertInstanceOf(KeyStore.PrivateKeyEntry::class.java, entry)
    }

    private fun pem(label: String, der: ByteArray): String {
        val body = Base64.getMimeEncoder(PEM_LINE, "\n".toByteArray()).encodeToString(der)
        return "-----BEGIN $label-----\n$body\n-----END $label-----\n"
    }

    /** Opens server mode as the daemon does, with the two PEM files named the way splice.toml names them: beside it. */
    private fun serve(): ServerMode {
        val server = ServerConfig(
            listen = "127.0.0.1:$port",
            commands = listOf("claude-team"),
            tlsCert = "fullchain.pem",
            tlsKey = "privkey.pem",
        )
        val machine = object : SharedHeads {
            override val machine: String = "gpu-box"

            override fun offered(): List<SharedHead> = emptyList()
        }
        val reach = ServerReach(TurnKey(MgmtKey(StatePaths(baseOverride = dir))), TestPorts.reserve())
        val opened = ServerMode(server, dir.resolve("server"), Dispatchers.IO, LogSink { })
        mode = opened
        opened.open(ServerAddress("127.0.0.1", port), machine, reach, dir)
        return opened
    }

    /** A client that trusts [certificate] and nothing else, and checks the name it is for, as Claude Code does. */
    private fun trusting(certificate: Certificate): HttpClient {
        val trusted = KeyStore.getInstance(KeyStore.getDefaultType())
        trusted.load(null, null)
        trusted.setCertificateEntry(ALIAS, certificate)
        val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        trust.init(trusted)
        val context = SSLContext.getInstance("TLS")
        context.init(null, trust.trustManagers, null)
        return HttpClient.newBuilder().sslContext(context).build()
    }

    @Test
    fun `with tls_cert and tls_key the listener answers a key over HTTPS, and plain HTTP gets no answer`() {
        val identity = identity()
        Files.writeString(dir.resolve("fullchain.pem"), pem("CERTIFICATE", identity.certificate.encoded))
        Files.writeString(dir.resolve("privkey.pem"), pem("PRIVATE KEY", identity.privateKey.encoded))
        val server = serve()
        val minted = runBlocking { server.keyApi.create(buildJsonObject { put("person", "tess@team.example") }) }
        val key = assertInstanceOf(ServerAnswer.Done::class.java, minted).body.getValue("key").jsonPrimitive.content
        val https = trusting(identity.certificate)

        val health = HttpRequest.newBuilder(URI("https://127.0.0.1:$port/health")).build()
        assertEquals(200, https.send(health, HttpResponse.BodyHandlers.ofString()).statusCode())
        val directory = HttpRequest.newBuilder(URI("https://127.0.0.1:$port/")).header("x-api-key", key).build()
        val offered = https.send(directory, HttpResponse.BodyHandlers.ofString())
        assertEquals(200, offered.statusCode())
        assertTrue(offered.body().contains("gpu-box"), offered.body())

        val plain = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/health")).timeout(Duration.ofSeconds(5)).build()
        assertThrows(IOException::class.java) {
            HttpClient.newHttpClient().send(plain, HttpResponse.BodyHandlers.ofString())
        }
    }
}

private const val ALIAS = "server"

// The throwaway store keytool writes the test's certificate into; it guards nothing.
private const val STORE_WORD = "changeit"

// The width PEM wraps its base64 at.
private const val PEM_LINE = 64
