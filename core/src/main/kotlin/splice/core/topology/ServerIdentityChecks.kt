package splice.core.topology

import splice.core.listen.IpLiterals
import splice.core.wire.NETWORK_PORT_MAX
import java.net.URI
import java.net.URISyntaxException

internal class ServerIdentityChecks(private val server: ServerConfig) {
    private val hostedDomain = Regex("(?:$DOMAIN_LABEL\\.)+$DOMAIN_LABEL", RegexOption.IGNORE_CASE)
    private val allowDomain = Regex("""^@[^@\s.]+(\.[^@\s.]+)+$""")
    private val headerName = Regex("[a-zA-Z][a-zA-Z0-9-]{1,63}")
    private val envName = Regex("[A-Za-z_][A-Za-z0-9_]*")
    private val reservedHeaderPrefixes = listOf("x-splice-", "x-accel-", "x-forwarded-")

    fun findings(): List<TopologyFinding> = listOfNotNull(
        TopologyFinding("server.admin_cidrs", "each entry must be an IP address or CIDR prefix")
            .takeUnless { server.adminCidrs.all { IpLiterals.prefix(it) != null } },
        TopologyFinding(
            "server.key_prefix",
            "use at most 24 ASCII letters, digits, hyphens or underscores, " +
                "starting with a letter and ending with an underscore",
        )
            .takeUnless { ServerKeyFormat.validMark(server.keyPrefix) },
        TopologyFinding("server.signin.hosted_domain", "must be a domain name without a leading @")
            .takeIf { server.signin?.hostedDomain?.let { !hostedDomain.matches(it) } == true },
    ) + server.forwardAuth?.let(::forwardAuth).orEmpty()

    private fun forwardAuth(auth: ServerForwardAuth): List<TopologyFinding> = listOfNotNull(
        TopologyFinding("$FORWARD_AUTH.allow", "each entry must be an email or @domain")
            .takeUnless { auth.allow.all { PersonEmails.read(it.trim()) != null || allowDomain.matches(it.trim()) } },
        TopologyFinding("$FORWARD_AUTH.session_seconds", "must be 1 to $MAX_SESSION_SECONDS seconds")
            .takeUnless { auth.sessionSeconds in 1..MAX_SESSION_SECONDS },
        TopologyFinding("$FORWARD_AUTH.email_header", "must be a custom identity header, such as Remote-User")
            .takeUnless { identityHeader(auth.emailHeader) },
        TopologyFinding("$FORWARD_AUTH.role_header", "must be a custom role header distinct from email_header")
            .takeIf { auth.roleHeader?.let { !identityHeader(it) || it.equals(auth.emailHeader, true) } == true },
    ) + browserClient(auth)

    private fun browserClient(auth: ServerForwardAuth): List<TopologyFinding> = listOfNotNull(
        TopologyFinding("$FORWARD_AUTH.browser_origin", "must be an HTTPS origin without a path, query or fragment")
            .takeIf { auth.browserOrigin?.let { !httpsOrigin(it) } == true },
        TopologyFinding("$FORWARD_AUTH.browser_origin", "browser sign-in also requires [server.signin]")
            .takeIf { auth.browserOrigin != null && server.signin == null },
        TopologyFinding("$FORWARD_AUTH.client_id", "a separate, nonblank client ID requires browser_origin")
            .takeIf { auth.clientId != null && (auth.clientId.isBlank() || auth.browserOrigin == null) },
        TopologyFinding("$FORWARD_AUTH.client_secret_env", "name an environment variable for the separate client ID")
            .takeIf { auth.clientSecretEnv?.let { !envName.matches(it) || auth.clientId == null } == true },
    )

    private fun identityHeader(name: String): Boolean {
        val lower = name.lowercase()
        val custom = lower.startsWith("x-") || lower.startsWith("remote-")
        val reserved = reservedHeaderPrefixes.any(lower::startsWith) || lower == "x-sendfile"
        return headerName.matches(name) && custom && !reserved
    }

    private fun httpsOrigin(text: String): Boolean {
        val uri = try {
            URI(text)
        } catch (_: URISyntaxException) {
            return false
        }
        val https = uri.scheme == "https" && uri.host != null && uri.userInfo == null
        val originOnly = uri.rawPath in setOf("", "/") && uri.rawQuery == null && uri.rawFragment == null
        val validPort = uri.port == -1 || uri.port in 1..NETWORK_PORT_MAX
        return https && originOnly && validPort
    }
}

private const val DOMAIN_LABEL = "[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?"
private const val FORWARD_AUTH = "server.forward_auth"

// why: a day is the longest a browser session may outlive an offboarding nobody performed by hand; a longer session
// belongs to a key, which an operator lists and revokes.
private const val MAX_SESSION_SECONDS = 86_400
