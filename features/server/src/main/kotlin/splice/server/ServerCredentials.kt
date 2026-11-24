package splice.server

import splice.server.web.BrowserCookieWire

/** Detects server credentials before caller-controlled fields cross a request boundary. */
internal class ServerCredentials(private val keys: ServerKeys) {
    fun inText(text: String): Boolean = keys.heldIn(text) || BrowserCookieWire.heldIn(text)
}
