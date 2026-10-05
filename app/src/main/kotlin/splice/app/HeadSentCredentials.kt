// NEW: the digest of each credential a head sends, heard under that head's key, so the console can name the login
// that carries the head's requests (2026-10-04: claude-splice's usage read the wrong login).
package splice.app

/** Hears the private digest of each credential a head just sent, never the token, under the head's own key. The
 *  head factory reports through it and the control plane answers it with the native login owner. */
internal fun interface HeadSentCredentials {
    fun sent(head: String, key: String)
}
