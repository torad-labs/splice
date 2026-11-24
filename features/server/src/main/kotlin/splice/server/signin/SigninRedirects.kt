package splice.server.signin

import splice.core.util.LoopbackOverride
import java.net.URI
import java.net.URISyntaxException

internal object SigninRedirects {
    fun laptop(text: String): Boolean {
        val uri = parse(text) ?: return false
        val loopback = uri.scheme == "http" && LoopbackOverride.isLoopback(text) && uri.port in 1..MAX_PORT
        return loopback && uri.rawPath == "/callback" && uri.rawQuery == null
    }

    fun provider(text: String, issuer: String): URI? {
        val uri = parse(text)?.takeIf { it.host != null } ?: return null
        val localTest = LoopbackOverride.isLoopback(issuer) && LoopbackOverride.isLoopback(text)
        return uri.takeIf { it.scheme == "https" || (it.scheme == "http" && localTest) }
    }

    fun dashboard(text: String): String? {
        val local = text.startsWith('/') && !text.startsWith("//")
        if (text.length > RETURN_PATH_LIMIT || !local) return null
        val uri = parse(text)?.takeUnless { it.isAbsolute || it.rawAuthority != null } ?: return null
        val path = uri.path
        return uri.toASCIIString().takeUnless {
            path.startsWith("//") || path.any { char -> char == '\\' || char.isISOControl() }
        }
    }

    private fun parse(text: String): URI? = try {
        URI(text).takeIf { it.rawUserInfo == null && it.rawFragment == null }
    } catch (_: URISyntaxException) {
        null
    }
}

private const val RETURN_PATH_LIMIT = 1_024
private const val MAX_PORT = 65_535
