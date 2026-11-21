// NEW: custom-provider (issue #680, cp-schema) — the keys a team's provider definition is written with, checked at
// boot beside every other finding (repo CLAUDE.md, "Boot fails closed"). Each key's behaviour is its own item's: this
// file only says whether a value can be acted on, naming the dotted path and never the value.
package splice.core.topology

import kotlinx.serialization.Serializable
import splice.core.config.UserHome
import splice.core.util.Cancellables
import java.io.ByteArrayInputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/** `served_window = { url, field }`: where a provider reports the window it serves, and the dotted path to it in the
 *  JSON answer (llama-server's /props: `default_generation_settings.n_ctx`). */
@Serializable
public data class ServedWindowConfig(
    val url: String = "",
    val field: String = "",
)

/** The `error_messages` word that shows a provider's own error words whole. */
public const val ERROR_MESSAGES_WHOLE: String = "whole"

/** The `error_messages` word for splice's filter, the default. */
public const val ERROR_MESSAGES_FILTERED: String = "filtered"

private const val NO_CERTIFICATE = "holds no PEM certificate"

/** A provider's `ca_file`: `~` expands, a relative path resolves against splice.toml's folder. */
public class ProviderCaFile(private val raw: String, private val configDir: Path?) {
    /** The file, or null when it is relative and there is no folder to resolve it against. */
    public fun path(): Path? {
        val expanded = Paths.get(UserHome.expand(raw.trim()))
        return when {
            expanded.isAbsolute -> expanded
            configDir != null -> configDir.resolve(expanded).normalize()
            else -> null
        }
    }

    /** Every certificate the bundle holds; throws when it cannot be read. */
    public fun certificates(): List<X509Certificate> {
        val file = checkNotNull(path()) { "a relative ca_file needs splice.toml's folder" }
        val bytes = Files.readAllBytes(file)
        return CertificateFactory.getInstance("X.509").generateCertificates(ByteArrayInputStream(bytes))
            .filterIsInstance<X509Certificate>()
    }

    /** The certificates the bundle holds, none when it cannot be read or what it holds is not PEM certificates (plain
     *  text, a block whose body is not base64): a CertificateException is the parser's word for a file that holds
     *  none, so it is a finding like an empty file, never a failure that stops boot. */
    private fun readable(): List<X509Certificate> =
        try {
            Cancellables.runCatchingCancellable { certificates() }.fold(onSuccess = { it }, onFailure = { emptyList() })
        } catch (_: CertificateException) {
            emptyList()
        }

    /** Why the bundle cannot be trusted, or null when it holds at least one certificate (or cannot be located here). */
    public fun problem(): String? {
        val file = path() ?: return null
        return when {
            !Files.exists(file) -> "names a file that does not exist"
            !Files.isReadable(file) || Files.isDirectory(file) -> "names a file splice cannot read"
            else -> if (readable().isEmpty()) NO_CERTIFICATE else null
        }
    }
}

/** The findings of the custom-provider keys, provider by provider. [configDir] resolves a relative `ca_file`; null
 *  leaves a relative one unchecked, since against any other folder it would refuse a file the daemon reads fine. */
public class CustomProviderChecks(private val topology: Topology, private val configDir: Path?) {
    public fun findings(): List<TopologyFinding> = topology.providers.flatMap { (key, provider) ->
        listOfNotNull(
            auth(key, provider.auth),
            caFile(key, provider),
            errorMessages(key, provider),
            servedWindow(key, provider),
        ) + outputs(key, provider)
    }

    private fun auth(key: String, auth: AuthConfig): TopologyFinding? {
        val why = when {
            auth.isCommand && auth.credentialCommand.isNullOrBlank() ->
                "kind = \"command\" needs credential_command, the command that prints the provider's key"
            !auth.isCommand && (auth.credentialCommand != null || auth.loginCommand != null) ->
                "credential_command and login_command go only with kind = \"command\"; set that kind, or remove them"
            else -> null
        }
        return why?.let { TopologyFinding("providers.$key.auth", it) }
    }

    private fun caFile(key: String, provider: ProviderConfig): TopologyFinding? {
        val raw = provider.caFile ?: return null
        val why = if (raw.isBlank()) "names no file" else ProviderCaFile(raw, configDir).problem()
        return why?.let {
            TopologyFinding(
                "providers.$key.ca_file",
                "$it; name a PEM bundle of the certificate authorities this provider's server is signed by",
            )
        }
    }

    private fun errorMessages(key: String, provider: ProviderConfig): TopologyFinding? =
        provider.errorMessages?.takeUnless { it == ERROR_MESSAGES_WHOLE || it == ERROR_MESSAGES_FILTERED }?.let {
            TopologyFinding(
                "providers.$key.error_messages",
                "must be \"$ERROR_MESSAGES_WHOLE\" (the provider's own words, as written) or " +
                    "\"$ERROR_MESSAGES_FILTERED\" (splice's filter, the default)",
            )
        }

    private fun servedWindow(key: String, provider: ProviderConfig): TopologyFinding? {
        val served = provider.servedWindow ?: return null
        val why = listOfNotNull(
            "url must be an absolute http(s) address".takeUnless { absoluteHttp(served.url) },
            "field must name the dotted path to the window in its JSON answer".takeIf { served.field.isBlank() },
        )
        return why.takeIf { it.isNotEmpty() }?.let {
            TopologyFinding("providers.$key.served_window", it.joinToString("; "))
        }
    }

    private fun outputs(key: String, provider: ProviderConfig): List<TopologyFinding> =
        provider.models.filter { (it.maxOutputTokens ?: 1) <= 0 }.map { model ->
            TopologyFinding(
                "providers.$key.models.max_output_tokens",
                "model ${model.id}: max_output_tokens must be a positive number of tokens; correct it or remove it",
            )
        }

    private fun absoluteHttp(url: String): Boolean = Cancellables.runCatchingCancellable { URI(url.trim()) }.fold(
        onSuccess = { uri -> (uri.scheme == "http" || uri.scheme == "https") && !uri.host.isNullOrBlank() },
        onFailure = { false },
    )
}
