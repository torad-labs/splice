package splice.head.admission

import io.ktor.server.application.Application
import io.ktor.server.application.serverConfig
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.netty.channel.Channel
import splice.http.ingress.HeapIngress
import java.util.concurrent.CompletableFuture

/** The staged close's grace, stretched past the wait below so only the client's FIN can win the race. */
internal const val GRACE_FAR_MS = 60_000L
internal const val CLOSE_WAIT_SECONDS = 15L

internal class HeapIngressTestServer {
    operator fun invoke(
        ingress: HeapIngress,
        channel: CompletableFuture<Channel>? = null,
        routes: Application.() -> Unit,
    ) =
        embeddedServer(
            Netty,
            serverConfig {
                module {
                    ingress.install(this)
                    routes()
                }
            },
        ) {
            connector {
                host = "127.0.0.1"
                port = 0
            }
            channelPipelineConfig = { pipeline ->
                ingress.install(pipeline)
                val _ = channel?.complete(pipeline.channel())
            }
        }
}
