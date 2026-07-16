// NEW: a thin wrapper over the Ktor streaming response so the gateway pipeline reads the body
// channel + headers without depending on ktor-client types directly.
package splice.spi

import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.utils.io.ByteReadChannel

public class UpstreamResponse(private val resp: HttpResponse) {
    public val status: Int get() = resp.status.value

    public fun header(name: String): String? = resp.headers[name]

    public suspend fun bodyChannel(): ByteReadChannel = resp.bodyAsChannel()

    public suspend fun bodyText(): String = resp.bodyAsText()
}

internal suspend fun HttpResponse.bodyText(): String = bodyAsText()
