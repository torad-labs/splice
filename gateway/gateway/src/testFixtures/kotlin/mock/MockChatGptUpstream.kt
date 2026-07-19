// PORT-OF: the mock upstream from server/test/codex-proxy.test.mjs @ 4ca99f7, 1:1 — scenario
// picked from a SCENARIO:<name> tag in body.instructions; /oauth/token counts refreshes;
// 'refresh' 401s on the old token; every streaming scenario's event sequence is verbatim
// (incl. the nonstream_tool mid-codepoint ✓ split and the prefill silent-then-stream shape).
// ADDED (named change, P2-MOCK slot): count_tokens has NO scenario here — the Kotlin router
// gives it a dedicated cheap handler; the old Node behavior (forwarding it as a real turn)
// is documented in the ledger, not reproduced.
package mock

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import splice.core.util.discard

class MockChatGptUpstream {
    val upstreamAuths = CopyOnWriteArrayList<Pair<String, String?>>()
    val upstreamBodies = CopyOnWriteArrayList<Pair<String, String>>()
    val abortedScenarios = CopyOnWriteArrayList<String>()
    val refreshCalls = AtomicInteger(0)

    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val pool = Executors.newCachedThreadPool()

    val port: Int get() = server.address.port
    val baseUrl: String get() = "http://127.0.0.1:$port"

    init {
        server.executor = pool
        server.createContext("/oauth/token") { ex ->
            refreshCalls.incrementAndGet()
            val body = """{"access_token":"tok-new","refresh_token":"refresh-2","id_token":"id-2"}"""
            ex.sendResponseHeaders(200, body.length.toLong())
            ex.responseBody.use { it.write(body.toByteArray()) }
        }
        server.createContext("/") { ex -> handle(ex) }
        server.start()
    }

    fun stop() {
        server.stop(0)
        pool.shutdownNow()
    }

    private fun sse(ex: HttpExchange, json: String) {
        ex.responseBody.write("data: $json\n\n".toByteArray())
        ex.responseBody.flush()
    }

    private fun handle(ex: HttpExchange) {
        val raw = ex.requestBody.readBytes().decodeToString()
        val body = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull()
        val instructions = body?.get("instructions")?.jsonPrimitive?.content.orEmpty()
        val scenario = Regex("SCENARIO:(\\w+)").find(instructions)?.groupValues?.get(1) ?: "basic"
        val auth = ex.requestHeaders.getFirst("Authorization")
        upstreamAuths.add(scenario to auth)
        upstreamBodies.add(scenario to raw)

        if (scenario == "refresh" && auth == "Bearer tok-old") {
            val err = """{"error":{"message":"token expired"}}"""
            ex.sendResponseHeaders(401, err.length.toLong())
            ex.responseBody.use { it.write(err.toByteArray()) }
            return
        }

        ex.responseHeaders.add("Content-Type", "text/event-stream")
        ex.sendResponseHeaders(200, 0)
        try {
            streamScenario(scenario, ex)
        } catch (abort: IOException) {
            // a broken pipe mid-stream is the drip scenario's EXPECTED client-abort exit
            if (scenario == "drip") abortedScenarios.add("drip")
            check(scenario == "drip") { "unexpected mid-stream I/O failure in scenario '$scenario': ${abort.message}" }
        } finally {
            runCatching { ex.responseBody.close() }.discard("test-server teardown")
            runCatching { ex.close() }.discard("test-server teardown")
        }
    }

    private fun streamScenario(scenario: String, ex: HttpExchange) {
        when (scenario) {
            "multipart" -> {
                sse(ex, """{"type":"response.output_item.added","output_index":0,"item":{"type":"reasoning"}}""")
                sse(ex, """{"type":"response.reasoning_summary_part.added","output_index":0}""")
                sse(ex, """{"type":"response.reasoning_summary_text.delta","output_index":0,"delta":"Part one."}""")
                sse(ex, """{"type":"response.reasoning_summary_text.done","output_index":0}""")
                sse(ex, """{"type":"response.reasoning_summary_part.done","output_index":0}""")
                sse(ex, """{"type":"response.reasoning_summary_part.added","output_index":0}""")
                sse(ex, """{"type":"response.reasoning_summary_text.delta","output_index":0,"delta":"Part two."}""")
                sse(ex, """{"type":"response.reasoning_summary_text.done","output_index":0}""")
                sse(ex, """{"type":"response.reasoning_summary_part.done","output_index":0}""")
                sse(ex, """{"type":"response.output_item.done","output_index":0}""")
                sse(ex, """{"type":"response.output_item.added","output_index":1,"item":{"type":"message"}}""")
                sse(ex, """{"type":"response.output_text.delta","output_index":1,"delta":"Answer text."}""")
                sse(ex, """{"type":"response.output_item.done","output_index":1}""")
                sse(
                    ex,
                    """{"type":"response.completed","response":{"id":"r1","status":"completed",""" +
                        """"output":[],"usage":{"input_tokens":10,"output_tokens":5}}}""",
                )
                ex.responseBody.write("data: [DONE]\n\n".toByteArray())
            }
            "toolcall" -> {
                sse(
                    ex,
                    """{"type":"response.output_item.added","output_index":0,""" +
                        """"item":{"type":"function_call","call_id":"call_abc","name":"get_thing"}}""",
                )
                sse(ex, """{"type":"response.function_call_arguments.delta","output_index":0,"delta":"{\"a\":"}""")
                sse(ex, """{"type":"response.function_call_arguments.delta","output_index":0,"delta":"1}"}""")
                sse(ex, """{"type":"response.function_call_arguments.done","output_index":0}""")
                sse(ex, """{"type":"response.output_item.done","output_index":0}""")
                sse(
                    ex,
                    """{"type":"response.completed","response":{"id":"r2","status":"completed",""" +
                        """"output":[],"usage":{"input_tokens":4,"output_tokens":2}}}""",
                )
            }
            "failed" -> sse(
                ex,
                """{"type":"response.failed","response":{"error":{"code":"server_error","message":"boom upstream"}}}""",
            )
            "overflow_sse" -> sse(
                ex,
                """{"type":"response.failed","response":{"error":{"code":"invalid_request_error",""" +
                    """"message":"Your input exceeds the context window of this model. Please reduce the length."}}}""",
            )
            "truncated" -> {
                sse(ex, """{"type":"response.output_item.added","output_index":0,"item":{"type":"message"}}""")
                sse(ex, """{"type":"response.output_text.delta","output_index":0,"delta":"partial answer"}""")
                // no response.completed
            }
            "idle" -> {
                sse(ex, """{"type":"response.output_item.added","output_index":0,"item":{"type":"message"}}""")
                sse(ex, """{"type":"response.output_text.delta","output_index":0,"delta":"partial"}""")
                Thread.sleep(5_000)
            }
            "zero_event_auth" -> {
                ex.responseBody.write(
                    "<html><body>401 Unauthorized: your session token has expired, please sign in again.</body></html>"
                        .toByteArray(),
                )
            }
            "zero_event_empty" -> {
                // deliberately nothing written — a true stall, not a diagnosable auth-shaped body
            }
            "prefill" -> {
                Thread.sleep(1_500) // silent past streamIdle — governed by firstByteTimeout
                sse(ex, """{"type":"response.output_item.added","output_index":0,"item":{"type":"message"}}""")
                sse(
                    ex,
                    """{"type":"response.output_text.delta","output_index":0,"delta":"summary after slow prefill"}""",
                )
                sse(ex, """{"type":"response.output_item.done","output_index":0}""")
                sse(
                    ex,
                    """{"type":"response.completed","response":{"usage":{"input_tokens":1000,"output_tokens":5}}}""",
                )
            }
            "drip" -> {
                sse(ex, """{"type":"response.output_item.added","output_index":0,"item":{"type":"message"}}""")
                while (true) {
                    sse(ex, """{"type":"response.output_text.delta","output_index":0,"delta":"drip "}""")
                    Thread.sleep(40)
                }
            }
            "bigout" -> {
                sse(ex, """{"type":"response.output_item.added","output_index":0,"item":{"type":"message"}}""")
                sse(ex, """{"type":"response.output_text.delta","output_index":0,"delta":"short summary"}""")
                sse(ex, """{"type":"response.output_item.done","output_index":0}""")
                sse(
                    ex,
                    """{"type":"response.completed","response":{"id":"rbig","status":"completed",""" +
                        """"output":[],"usage":{"input_tokens":500,"output_tokens":200000}}}""",
                )
            }
            "nonstream_tool" -> {
                val evt = """{"type":"response.completed","response":{"id":"r3","status":"completed","output":[""" +
                    """{"type":"reasoning","summary":[{"type":"summary_text","text":"Because reasons that are long enough to mirror."}]},""" +
                    """{"type":"message","content":[{"type":"output_text","text":"héllo — ✓ done"}]},""" +
                    """{"type":"function_call","call_id":"call_xyz","name":"fn_x","arguments":"{\"q\":\"z\"}"}""" +
                    """],"usage":{"input_tokens":3,"output_tokens":2}}}"""
                val buf = "data: $evt\n\n".toByteArray()
                val check = "✓".toByteArray()
                val at = buf.toList()
                    .windowed(check.size)
                    .indexOfFirst { it == check.toList() } + 1
                ex.responseBody.write(buf.copyOfRange(0, at)) // split INSIDE the 3-byte ✓
                ex.responseBody.flush()
                Thread.sleep(20)
                ex.responseBody.write(buf.copyOfRange(at, buf.size))
            }
            "compactish" -> {
                sse(ex, """{"type":"response.output_item.added","output_index":0,"item":{"type":"reasoning"}}""")
                sse(
                    ex,
                    """{"type":"response.reasoning_summary_text.delta","output_index":0,""" +
                        """"delta":"Goal: port the proxy. Decisions: split modules. Next: tests."}""",
                )
                sse(ex, """{"type":"response.output_item.done","output_index":0}""")
                sse(
                    ex,
                    """{"type":"response.completed","response":{"id":"rc","status":"completed",""" +
                        """"output":[],"usage":{"input_tokens":9,"output_tokens":3}}}""",
                )
            }
            "replaystream" -> {
                sse(ex, """{"type":"response.output_item.added","output_index":0,"item":{"type":"reasoning"}}""")
                sse(
                    ex,
                    """{"type":"response.reasoning_summary_text.delta","output_index":0,""" +
                        """"delta":"Long enough reasoning summary to mirror into text."}""",
                )
                sse(
                    ex,
                    """{"type":"response.output_item.done","output_index":0,""" +
                        """"item":{"type":"reasoning","id":"rs_stream","encrypted_content":"ENC-STREAM"}}""",
                )
                sse(ex, """{"type":"response.output_item.added","output_index":1,"item":{"type":"message"}}""")
                sse(ex, """{"type":"response.output_text.delta","output_index":1,"delta":"answer"}""")
                sse(ex, """{"type":"response.output_item.done","output_index":1}""")
                sse(
                    ex,
                    """{"type":"response.completed","response":{"id":"rrs","status":"completed",""" +
                        """"output":[],"usage":{"input_tokens":7,"output_tokens":4}}}""",
                )
            }
            else -> { // basic / refresh-after-refresh
                sse(ex, """{"type":"response.output_item.added","output_index":0,"item":{"type":"message"}}""")
                sse(ex, """{"type":"response.output_text.delta","output_index":0,"delta":"ok after auth"}""")
                sse(ex, """{"type":"response.output_item.done","output_index":0}""")
                sse(
                    ex,
                    """{"type":"response.completed","response":{"id":"r4","status":"completed",""" +
                        """"output":[],"usage":{"input_tokens":1,"output_tokens":1}}}""",
                )
            }
        }
    }
}
