package splice.core.topology

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/** cp-schema: a `ca_file` that holds no certificate is a finding at providers.<key>.ca_file, listed with every other
 *  finding, whatever the file holds instead, never a failure that stops boot. */
class CaFileFindingTest {
    @TempDir
    lateinit var dir: Path

    private fun caFileFindings(content: String): List<String> {
        val file = dir.resolve("bad.pem")
        Files.writeString(file, content)
        val provider = ProviderConfig(
            dialect = Dialect.OPENAI_CHAT,
            baseUrl = "https://ai.acme.example/v1",
            auth = AuthConfig(kind = "api-key"),
            caFile = file.toString(),
        )
        return CustomProviderChecks(Topology(providers = mapOf("acme" to provider)), dir).findings()
            .map { it.path }
            .filter { it.endsWith("ca_file") }
    }

    @Test
    fun `a ca_file holding plain text is a finding`() {
        assertEquals(listOf("providers.acme.ca_file"), caFileFindings("this is not a certificate\n"))
    }

    @Test
    fun `a ca_file holding a PEM block whose body is not base64 is a finding`() {
        val pem = "-----BEGIN CERTIFICATE-----\n!!!! not base64 ????\n-----END CERTIFICATE-----\n"
        assertEquals(listOf("providers.acme.ca_file"), caFileFindings(pem))
    }
}
