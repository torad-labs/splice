// `splice status`'s mode lines (modes/README.md), as it reads them from a daemon's GET /api/modes: a mode on for one
// caller alone, as `on_for` leaves it while splice.toml says nothing, is said to be on for that caller, never just off;
// a mode on or off everywhere reads as it always has.
package splice.app.cli.status

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import splice.core.util.EnvReader
import java.net.InetSocketAddress
import java.nio.file.Path

class ModeLinesTest {
    /** A row of GET /api/modes: [heads] and [callers] as `{on, from}` tables, [every] the every-head value. */
    private fun row(name: String, every: Boolean, heads: Map<String, Boolean>, callers: Map<String, Boolean>?): String {
        fun table(on: Map<String, Boolean>) = on.entries.joinToString(",", "{", "}") { (k, v) ->
            """"$k":{"on":$v,"from":"manifest"}"""
        }
        val byCaller = callers?.let { ""","callers":${table(it)}""" }.orEmpty()
        return """{"name":"$name","kind":"mcp","server":"$name","tools":[{"offered_as":"mcp__${name}__go"}],""" +
            """"callers_off":[],"on":{"value":$every,"from":"manifest"},"heads":${table(heads)}$byCaller}"""
    }

    private fun lines(tmp: Path, vararg rows: String): List<String> {
        val state = tmp.resolve("state")
        val env = EnvReader {
            mapOf("SPLICE_STATE_DIR" to state.toString(), "SPLICE_CONFIG" to tmp.resolve("none.toml").toString())[it]
        }
        MgmtKey(StatePaths(baseOverride = state, envReader = env)).get()
        val body = """{"modes":[${rows.joinToString(",")}]}""".toByteArray()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/modes") { ex ->
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            return ModeLines().lines(server.address.port, env)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `a mode on for Desktop alone says so, and one on for a head and Desktop names both`(@TempDir tmp: Path) {
        val callers = mapOf("door" to false, "head" to false, "desktop" to true)
        val printed = lines(
            tmp,
            row("visual", every = false, heads = mapOf("acme" to false), callers = callers),
            row("chart", every = false, heads = mapOf("acme" to true), callers = callers),
        )
        assertEquals("  mode  visual  on for desktop  mcp  mcp__visual__go", printed[0])
        assertEquals("  mode  chart  on acme, for desktop  mcp  mcp__chart__go", printed[1])
    }

    @Test
    fun `a mode on or off for every caller reads as before, from a daemon with callers or without`(
        @TempDir tmp: Path,
    ) {
        val all = mapOf("door" to true, "head" to true, "desktop" to true)
        val none = mapOf("door" to false, "head" to false, "desktop" to false)
        val printed = lines(
            tmp,
            row("on", every = true, heads = mapOf("acme" to true), callers = all),
            row("off", every = false, heads = mapOf("acme" to false), callers = none),
            row("older", every = false, heads = mapOf("acme" to false), callers = null),
        )
        assertEquals(
            listOf(
                "  mode  on  on acme  mcp  mcp__on__go",
                "  mode  off  off  mcp  mcp__off__go",
                "  mode  older  off  mcp  mcp__older__go",
            ),
            printed,
        )
    }
}
