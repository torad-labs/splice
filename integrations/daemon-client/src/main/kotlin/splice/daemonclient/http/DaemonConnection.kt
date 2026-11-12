package splice.daemonclient.http

import java.net.HttpURLConnection
import java.net.URI

/** The HTTP connection receives its timeout from the probe that owns the wait. */
internal object DaemonConnection {
    fun open(url: String, timeoutMs: Int, method: String = "GET", bearer: String? = null): HttpURLConnection {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.requestMethod = method
        bearer?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
        connection.connectTimeout = timeoutMs
        connection.readTimeout = timeoutMs
        return connection
    }
}
