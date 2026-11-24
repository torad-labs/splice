package splice.app.cli.peer

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.control.peer.PEER_MCP_ENDPOINT
import splice.core.util.EnvReader
import splice.sessions.peer.owner.PeerOwnerProcesses
import splice.sessions.peer.store.PeerRequest
import splice.sessions.peer.store.PeerStore
import java.net.InetSocketAddress
import java.nio.file.Path

class PeerMcpRotationTest {
    @Test
    fun `an open MCP stdio session keeps working after peer key rotation`(@TempDir dir: Path): Unit = runBlocking {
        val store = PeerStore(dir)
        val owner = PeerOwnerProcesses().ancestors().first()
        val name = "running-peer"
        store.register(PeerRequest(name, dir.toString(), listOf("*"), owner, harness = "claude-code"), emptySet())
        val reply = """{"jsonrpc":"2.0","id":1,"result":{"isError":false}}"""
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext(PEER_MCP_ENDPOINT) { exchange ->
            exchange.use {
                val caller = store.principal(exchange.requestHeaders.getFirst("Authorization"))
                exchange.requestBody.readAllBytes()
                if (caller?.name == name) {
                    val bytes = reply.encodeToByteArray()
                    exchange.sendResponseHeaders(200, bytes.size.toLong())
                    exchange.responseBody.write(bytes)
                } else {
                    exchange.sendResponseHeaders(401, -1)
                }
            }
        }
        server.start()
        try {
            val stdio = PeerMcpStdio(
                EnvReader {
                    when (it) {
                        "SPLICE_CONFIG" -> dir.resolve("splice.toml").toString()
                        "SPLICE_CONTROL_PORT" -> server.address.port.toString()
                        else -> null
                    }
                },
            )
            val call = """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"list_peers"}}"""
            assertEquals(reply, stdio.answer(call))
            checkNotNull(store.rotate(name))
            assertEquals(reply, stdio.answer(call))
        } finally {
            server.stop(0)
        }
    }
}
