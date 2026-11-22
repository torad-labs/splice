package splice.provider.antigravity.door

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.util.LogSink
import java.nio.file.Files
import java.nio.file.Path

class AntigravityDoorEngineTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `guard blocks Claude Code CLI requests and allows Desktop Chat with custom tools`() {
        val chatTools = buildJsonObject {
            putJsonArray("tools") {
                add(
                    buildJsonObject {
                        put("name", "create_artifact")
                        putJsonObject("input_schema") { put("type", "object") }
                    },
                )
            }
        }
        assertFalse(DoorAgyGuard.isClaudeCodeRequest("Anthropic/JS 0.39.0", null, chatTools))
        assertTrue(DoorAgyGuard.isClaudeCodeRequest("claude-cli/2.1.330 (external, cli)", null, chatTools))
        assertTrue(DoorAgyGuard.isClaudeCodeRequest("node-fetch", "cli", chatTools))

        val cliTools = buildJsonObject {
            putJsonArray("tools") {
                listOf("Bash", "Read", "Edit").forEach { name ->
                    add(
                        buildJsonObject {
                            put("name", name)
                            putJsonObject("input_schema") { put("type", "object") }
                        },
                    )
                }
            }
        }
        assertTrue(DoorAgyGuard.isClaudeCodeRequest("Anthropic/JS 0.39.0", null, cliTools))
    }

    @Test
    fun `parser includes Anthropic top-level system prompt and project instructions in initialPrefix`() {
        val stringReq = buildJsonObject {
            put("model", "gem-4-neon")
            put("system", "You are inside Project Alpha.")
            putJsonArray("messages") {
                add(buildJsonObject { put("role", "user"); put("content", "hi") })
            }
        }
        val parsedStr = DoorAgyRequestParser.parse(stringReq, "gem-4-neon")
        assertTrue(parsedStr.transcript.openingPrefix.orEmpty().contains("You are inside Project Alpha."))

        val arrayReq = buildJsonObject {
            put("model", "gem-4-neon")
            putJsonArray("system") {
                add(buildJsonObject { put("type", "text"); put("text", "Base persona.") })
                add(buildJsonObject { put("type", "text"); put("text", "Project rule.") })
            }
            putJsonArray("messages") {
                add(buildJsonObject { put("role", "user"); put("content", "first") })
                add(buildJsonObject { put("role", "assistant"); put("content", "ok") })
                add(buildJsonObject { put("role", "user"); put("content", "second") })
            }
        }
        val parsedArr = DoorAgyRequestParser.parse(arrayReq, "gem-4-neon")
        val prefix = parsedArr.transcript.openingPrefix.orEmpty()
        assertTrue(prefix.contains("Base persona.") && prefix.contains("Project rule."), prefix)
        assertTrue(prefix.contains("User: first") && prefix.contains("Assistant: ok"), prefix)
    }

    @Test
    fun `streams thinking and text and reuses session across multi-turn chat`() = runBlocking {
        val script = writeFakeAgy(multiTurnAgyScript())
        AntigravityDoorEngine(this, Dispatchers.IO, LogSink {}, agy = listOf(script.toString())).use { engine ->
            val sink1 = RecordingSink()
            val res1 = engine.serve(chatRequest(listOf("user" to "hello")), "gem-4-neon", sink1)
            assertEquals(DoorAgyTurnResult.Completed(false, false, 10, 5), res1)
            assertEquals(listOf("thinking-1"), sink1.thinking)
            assertEquals(listOf("reply-1"), sink1.text)

            val sink2 = RecordingSink()
            val turns = listOf("user" to "hello", "assistant" to "reply-1", "user" to "follow up")
            val res2 = engine.serve(chatRequest(turns), "gem-4-neon", sink2)
            assertEquals(DoorAgyTurnResult.Completed(false, false, 10, 5), res2)
            assertEquals(listOf("thinking-2"), sink2.thinking)
            assertEquals(listOf("reply-2"), sink2.text)
        }
    }

    @Test
    fun `bridges Desktop Chat tools via loopback MCP and rejects built-in agy tools`() = runBlocking {
        val permLog = tempDir.resolve("perm.log")
        val script = writeFakeAgy(toolBridgeAgyScript(permLog))
        val tools = lookupPriceTools()
        AntigravityDoorEngine(this, Dispatchers.IO, LogSink {}, agy = listOf(script.toString())).use { engine ->
            val sink1 = RecordingSink()
            val res1 = engine.serve(initialToolRequest(tools), "gem-4-neon", sink1)
            assertTrue(res1 is DoorAgyTurnResult.Completed && res1.hasToolUse, "expected tool_use pause: $res1")
            val (toolId, toolName, toolInput) = sink1.tools.single()
            assertEquals("lookup_price", toolName)
            assertEquals("""{"symbol":"NVDA"}""", toolInput)

            val sink2 = RecordingSink()
            val req2 = toolResultRequest(tools, toolId, toolName, toolInput, "$142.50")
            val res2 = engine.serve(req2, "gem-4-neon", sink2)
            assertEquals(DoorAgyTurnResult.Completed(false, false, 20, 12), res2)
            assertEquals(listOf("Price is $142.50"), sink2.text)

            val perms = Files.readString(permLog)
            assertTrue(perms.contains("reject-1"), "built-in tool must be rejected: $perms")
            assertTrue(perms.contains("allow-2"), "bridged MCP tool must be allowed: $perms")
        }
    }

    private fun lookupPriceTools(): JsonArray = buildJsonArray {
        add(
            buildJsonObject {
                put("name", "lookup_price")
                put("description", "Lookup a ticker price")
                putJsonObject("input_schema") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("symbol") { put("type", "string") }
                    }
                }
            },
        )
    }

    private fun initialToolRequest(tools: JsonArray): JsonObject = buildJsonObject {
        put("model", "gem-4-neon")
        put("tools", tools)
        putJsonArray("messages") {
            add(buildJsonObject { put("role", "user"); put("content", "what is NVDA price?") })
        }
    }

    private fun toolResultRequest(
        tools: JsonArray,
        toolId: String,
        toolName: String,
        toolInput: String,
        resultText: String,
    ): JsonObject = buildJsonObject {
        put("model", "gem-4-neon")
        put("tools", tools)
        putJsonArray("messages") {
            add(buildJsonObject { put("role", "user"); put("content", "what is NVDA price?") })
            add(
                buildJsonObject {
                    put("role", "assistant")
                    putJsonArray("content") {
                        add(
                            buildJsonObject {
                                put("type", "tool_use")
                                put("id", toolId)
                                put("name", toolName)
                                put("input", Json.parseToJsonElement(toolInput))
                            },
                        )
                    }
                },
            )
            add(
                buildJsonObject {
                    put("role", "user")
                    putJsonArray("content") {
                        add(
                            buildJsonObject {
                                put("type", "tool_result")
                                put("tool_use_id", toolId)
                                put("content", resultText)
                            },
                        )
                    }
                },
            )
        }
    }

    private fun multiTurnAgyScript(): String = """
        #!/usr/bin/env bash
        set -euo pipefail
        prompt_count=0
        while IFS= read -r line; do
          if [[ "${'$'}line" == *'"method":"initialize"'* ]]; then
            id=${'$'}(echo "${'$'}line" | sed -n 's/.*"id":\([0-9]*\).*/\1/p')
            echo "{\"jsonrpc\":\"2.0\",\"id\":${'$'}id,\"result\":{\"protocolVersion\":1,\"agentCapabilities\":{\"promptCapabilities\":{\"image\":true}}}}"
          elif [[ "${'$'}line" == *'"method":"session/new"'* ]]; then
            id=${'$'}(echo "${'$'}line" | sed -n 's/.*"id":\([0-9]*\).*/\1/p')
            echo "{\"jsonrpc\":\"2.0\",\"id\":${'$'}id,\"result\":{\"sessionId\":\"sess-1\",\"configOptions\":[{\"id\":\"model\",\"currentValue\":\"gem-4-neon\",\"options\":[{\"value\":\"gem-4-neon\",\"name\":\"Gemini 4 Neon\"}]}]}}"
          elif [[ "${'$'}line" == *'"method":"session/set_config_option"'* ]]; then
            id=${'$'}(echo "${'$'}line" | sed -n 's/.*"id":\([0-9]*\).*/\1/p')
            echo "{\"jsonrpc\":\"2.0\",\"id\":${'$'}id,\"result\":{}}"
          elif [[ "${'$'}line" == *'"method":"session/prompt"'* ]]; then
            prompt_count=${'$'}((prompt_count + 1))
            id=${'$'}(echo "${'$'}line" | sed -n 's/.*"id":\([0-9]*\).*/\1/p')
            echo "{\"jsonrpc\":\"2.0\",\"method\":\"session/update\",\"params\":{\"sessionId\":\"sess-1\",\"update\":{\"sessionUpdate\":\"agent_thought_chunk\",\"content\":{\"type\":\"text\",\"text\":\"thinking-${'$'}prompt_count\"}}}}"
            echo "{\"jsonrpc\":\"2.0\",\"method\":\"session/update\",\"params\":{\"sessionId\":\"sess-1\",\"update\":{\"sessionUpdate\":\"agent_message_chunk\",\"content\":{\"type\":\"text\",\"text\":\"reply-${'$'}prompt_count\"}}}}"
            echo "{\"jsonrpc\":\"2.0\",\"id\":${'$'}id,\"result\":{\"stopReason\":\"end_turn\",\"usage\":{\"inputTokens\":10,\"outputTokens\":5}}}"
          fi
        done
    """.trimIndent()

    private fun toolBridgeAgyScript(permLog: Path): String = """
        #!/usr/bin/env bash
        set -euo pipefail
        mcp_url=""
        mcp_auth=""
        while IFS= read -r line; do
          if [[ "${'$'}line" == *'"method":"initialize"'* ]]; then
            id=${'$'}(echo "${'$'}line" | sed -n 's/.*"id":\([0-9]*\).*/\1/p')
            echo "{\"jsonrpc\":\"2.0\",\"id\":${'$'}id,\"result\":{\"protocolVersion\":1,\"agentCapabilities\":{\"promptCapabilities\":{\"image\":true}}}}"
          elif [[ "${'$'}line" == *'"method":"session/new"'* ]]; then
            id=${'$'}(echo "${'$'}line" | sed -n 's/.*"id":\([0-9]*\).*/\1/p')
            mcp_url=${'$'}(echo "${'$'}line" | sed -n 's/.*"url":"\([^"]*\)".*/\1/p')
            mcp_auth=${'$'}(echo "${'$'}line" | sed -n 's/.*"value":"\(Bearer [^"]*\)".*/\1/p')
            echo "{\"jsonrpc\":\"2.0\",\"id\":${'$'}id,\"result\":{\"sessionId\":\"sess-tools\",\"configOptions\":[{\"id\":\"model\",\"currentValue\":\"gem-4-neon\",\"options\":[{\"value\":\"gem-4-neon\",\"name\":\"Gemini 4 Neon\"}]}]}}"
          elif [[ "${'$'}line" == *'"method":"session/set_config_option"'* ]]; then
            id=${'$'}(echo "${'$'}line" | sed -n 's/.*"id":\([0-9]*\).*/\1/p')
            echo "{\"jsonrpc\":\"2.0\",\"id\":${'$'}id,\"result\":{}}"
          elif [[ "${'$'}line" == *'"method":"session/prompt"'* ]]; then
            id=${'$'}(echo "${'$'}line" | sed -n 's/.*"id":\([0-9]*\).*/\1/p')
            echo '{"jsonrpc":"2.0","id":91,"method":"session/request_permission","params":{"toolCall":{"toolCallId":"tc-shell","name":"run_command","kind":"execute"},"options":[{"optionId":"allow-1","kind":"allow_once"},{"optionId":"reject-1","kind":"reject_once"}]}}'
            IFS= read -r perm1
            echo "shell:${'$'}perm1" >> "$permLog"
            echo '{"jsonrpc":"2.0","id":92,"method":"session/request_permission","params":{"toolCall":{"toolCallId":"tc-mcp","name":"mcp_chat_lookup_price","kind":"other"},"options":[{"optionId":"allow-2","kind":"allow_once"},{"optionId":"reject-2","kind":"reject_once"}]}}'
            IFS= read -r perm2
            echo "mcp:${'$'}perm2" >> "$permLog"
            mcp_res=${'$'}(curl -sS -X POST "${'$'}mcp_url" -H "Authorization: ${'$'}mcp_auth" -H "Content-Type: application/json" -d '{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"lookup_price","arguments":{"symbol":"NVDA"}}}')
            val=${'$'}(echo "${'$'}mcp_res" | sed -n 's/.*"text":"\([^"]*\)".*/\1/p')
            echo "{\"jsonrpc\":\"2.0\",\"method\":\"session/update\",\"params\":{\"sessionId\":\"sess-tools\",\"update\":{\"sessionUpdate\":\"agent_message_chunk\",\"content\":{\"type\":\"text\",\"text\":\"Price is ${'$'}val\"}}}}"
            echo "{\"jsonrpc\":\"2.0\",\"id\":${'$'}id,\"result\":{\"stopReason\":\"end_turn\",\"usage\":{\"inputTokens\":20,\"outputTokens\":12}}}"
          fi
        done
    """.trimIndent()

    private fun chatRequest(turns: List<Pair<String, String>>): JsonObject = buildJsonObject {
        put("model", "gem-4-neon")
        putJsonArray("messages") {
            turns.forEach { (role, content) ->
                add(buildJsonObject { put("role", role); put("content", content) })
            }
        }
    }

    private fun writeFakeAgy(script: String): Path {
        val file = tempDir.resolve("fake-agy-${System.nanoTime()}.sh")
        Files.writeString(file, script + "\n")
        file.toFile().setExecutable(true)
        return file
    }

    private class RecordingSink : DoorAgySink {
        val thinking = mutableListOf<String>()
        val text = mutableListOf<String>()
        val tools = mutableListOf<Triple<String, String, String>>()

        override suspend fun onThinking(text: String) {
            thinking += text
        }

        override suspend fun onText(text: String) {
            this.text += text
        }

        override suspend fun onToolUse(id: String, name: String, inputJson: String) {
            tools += Triple(id, name, inputJson)
        }
    }
}
