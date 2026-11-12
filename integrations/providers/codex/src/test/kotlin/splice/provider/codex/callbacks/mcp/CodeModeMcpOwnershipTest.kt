package splice.provider.codex.callbacks.mcp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.provider.codex.CodeModeBaseline
import splice.provider.codex.CodeModeBridgeConfig
import splice.provider.codex.CodeModeIssuedStep
import splice.provider.codex.CodeModeNativeContinuity
import splice.provider.codex.CodeModeOrigin
import splice.provider.codex.CodeModePending
import splice.provider.codex.CodeModePhase
import splice.provider.codex.CodeModeProgress
import splice.provider.codex.CodeModeRecord
import splice.provider.codex.CodeModeStateLocation
import splice.provider.codex.CodexCodeModeBridge
import splice.provider.codex.CodexCodeModeValidation
import splice.provider.codex.state.CodeModeExpiredHistory
import splice.provider.codex.state.CodeModeKeyLocks
import splice.provider.codex.state.CodeModeRegistryAccess
import splice.provider.codex.state.query.CodeModeRecordQueries
import splice.upstream.codemode.CodeModeResult
import splice.upstream.codemode.CodeModeResultDisposition
import splice.upstream.codemode.CodeModeResultOrigin
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

class CodeModeMcpOwnershipTest {
    @Test
    fun `a callback stays ordinary if its owner changes while a transport read is delayed`() {
        val state = QueryState()
        val original = turn(result())
        val before = state.snapshot()
        val reading = CountDownLatch(1)
        val release = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(2)
        try {
            val resolver = CodeModeMcpReceipts { id, name, arguments ->
                reading.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                raw(isError = true, identity = identity(id, name, arguments))
            }
            val resolved = workers.submit<CodexCodeModeBridge.Turn> {
                resolver.resolve(original, before)
            }
            check(reading.await(5, TimeUnit.SECONDS))
            val changed = workers.submit<String> {
                state.access.withKey("key") {
                    state.call.exposed = false
                    state.record.progress.pending.clear()
                    state.record.issued.clear()
                    state.record.sessionId = "changed-after-snapshot"
                    "owner changed"
                }
            }
            assertEquals("owner changed", changed.get(5, TimeUnit.SECONDS))
            release.countDown()
            val captured = resolved.get(5, TimeUnit.SECONDS)
            val actual = resolver.reconcile(original, captured, before, state.snapshot()).toolResults.single()
            assertEquals(result(), actual)
        } finally {
            release.countDown()
            workers.shutdownNow()
        }
    }

    @Test
    fun `an accepted generic value never gains a later capture and an accepted MCP value keeps its payload`() {
        val state = QueryState()
        val generic = result()
        state.accept(generic)
        val genericOwners = state.snapshot()
        val captured = generic.copy(payload = raw(isError = true), origin = CodeModeResultOrigin.MCP)
        state.accept(captured)
        val later = raw(isError = true, identity = buildJsonObject { put("different", "later transport value") })
        val resolver = CodeModeMcpReceipts { _, _, _ -> later }
        assertEquals(generic, resolver.resolve(turn(generic), genericOwners).toolResults.single())
        val mcpOwners = state.snapshot()
        state.accept(generic)
        assertEquals(captured, resolver.resolve(turn(generic), mcpOwners).toolResults.single())
        val changed = generic.copy(output = "edited client output")
        assertEquals(changed, resolver.resolve(turn(changed), mcpOwners).toolResults.single())
        val otherVerdict = generic.copy(isError = false)
        assertEquals(otherVerdict, resolver.resolve(turn(otherVerdict), mcpOwners).toolResults.single())
    }

    @Test
    fun `foreign sessions hidden calls ambiguous owners and conflicting issued identities keep ordinary text`() {
        val resolver = CodeModeMcpReceipts { _, _, _ -> raw(isError = true) }
        val foreign = QueryState()
        assertEquals(
            result(),
            resolver.resolve(turn(result()), foreign.snapshot("another-session")).toolResults.single(),
        )
        val hidden = QueryState()
        hidden.access.withKey("key") { hidden.call.exposed = false }
        assertEquals(result(), resolver.resolve(turn(result()), hidden.snapshot()).toolResults.single())
        val repeated = QueryState()
        repeated.access.withKey("key") {
            repeated.records += repeated.record.copy(id = "another-owner").also { it.sessionId = "session" }
        }
        assertEquals(result(), resolver.resolve(turn(result()), repeated.snapshot()).toolResults.single())
        val conflict = QueryState()
        conflict.access.withKey("key") {
            conflict.record.issued += CodeModeIssuedStep(
                "digest",
                listOf(conflict.call.copy(name = "mcp__other__tool")),
            )
        }
        assertEquals(result(), resolver.resolve(turn(result()), conflict.snapshot()).toolResults.single())
    }

    @Test
    fun `a captured error verdict must equal the ordinary callback verdict`() {
        val state = QueryState()
        val resolver = CodeModeMcpReceipts { _, _, _ -> raw(isError = false) }
        assertEquals(result(), resolver.resolve(turn(result()), state.snapshot()).toolResults.single())
    }

    @Test
    fun `a generic acceptance made during capture lookup stays generic and a prior MCP acceptance wins`() {
        val state = QueryState()
        val original = turn(result())
        val before = state.snapshot()
        val later = raw(isError = true, identity = buildJsonObject { put("value", "later") })
        val resolver = CodeModeMcpReceipts { _, _, _ -> later }
        val captured = resolver.resolve(original, before)
        state.accept(result())
        assertEquals(result(), resolver.reconcile(original, captured, before, state.snapshot()).toolResults.single())
        val first = result().copy(
            origin = CodeModeResultOrigin.MCP,
            payload = raw(isError = true, identity = buildJsonObject { put("value", "first accepted") }),
        )
        state.accept(first)
        assertEquals(first, resolver.reconcile(original, captured, before, state.snapshot()).toolResults.single())
    }

    @Test
    fun `evicted replaced hidden or reissued callback owners cannot authorize a completed capture lookup`() {
        val mutations: List<(QueryState) -> Unit> = listOf(
            { state -> state.records.clear() },
            { state -> state.records[0] = state.record.copy(id = "replacement").also { it.sessionId = "session" } },
            { state -> state.call.exposed = false },
            { state -> state.record.progress.pending[0] = state.call.copy(name = "mcp__other__tool") },
        )
        mutations.forEach { mutate ->
            val state = QueryState()
            val original = turn(result())
            val before = state.snapshot()
            val resolver = CodeModeMcpReceipts { _, _, _ -> raw(isError = true) }
            val captured = resolver.resolve(original, before)
            state.access.withKey("key") { mutate(state) }
            assertEquals(
                result(),
                resolver.reconcile(original, captured, before, state.snapshot()).toolResults.single(),
            )
        }
    }

    @Test
    fun `late MCP metadata cannot fork generic acceptance but changed text verdict or accepted MCP value can`(
        @TempDir tmp: Path,
    ) {
        val validation = CodexCodeModeValidation(
            CodeModeBridgeConfig(
                runtimes = {
                    throw UnsupportedOperationException(
                        "the validation fixture supplies no execution runtime",
                    )
                },
                state = CodeModeStateLocation(tmp.resolve("records"), tmp.resolve("legacy.json")),
            ),
        )
        val state = QueryState()
        val resolver = CodeModeMcpReceipts { _, _, _ -> raw(isError = true) }
        val captured = resolver.resolve(turn(result()), state.snapshot())
        state.accept(result())
        assertEquals(false, validation.conflicts(state.record, captured))
        val receipt = captured.toolResults.single()
        val textEdited = captured.copy(toolResults = listOf(receipt.copy(output = "edited text")))
        assertEquals(true, validation.conflicts(state.record, textEdited))
        val verdictEdited = captured.copy(toolResults = listOf(receipt.copy(isError = false)))
        assertEquals(true, validation.conflicts(state.record, verdictEdited))
        state.accept(receipt)
        val changed = receipt.copy(
            payload = raw(isError = true, identity = buildJsonObject { put("different", true) }),
        )
        assertEquals(true, validation.conflicts(state.record, captured.copy(toolResults = listOf(changed))))
        val shaped = resolver.resolve(
            turn(result().copy(payload = JsonPrimitive("ordinary\n text"))),
            QueryState().snapshot(),
        )
        state.accept(result())
        assertEquals(true, validation.conflicts(state.record, shaped))
    }

    private fun turn(result: CodeModeResult): CodexCodeModeBridge.Turn = CodexCodeModeBridge.Turn(
        "session",
        "conversation",
        "gpt-5.6-sol",
        setOf("mcp__capture__inspect"),
        listOf(result),
    )

    private fun result(): CodeModeResult = CodeModeResult(
        "callback",
        "ordinary text",
        isError = true,
        disposition = CodeModeResultDisposition.TOOL_OUTPUT,
    )

    private fun raw(isError: Boolean, identity: JsonObject? = null): JsonObject = buildJsonObject {
        put("content", JsonArray(emptyList()))
        put("structuredContent", identity ?: buildJsonObject { put("actual", true) })
        put("isError", isError)
    }

    private fun identity(id: String, name: String, arguments: JsonObject): JsonObject = buildJsonObject {
        put("callbackId", id); put("name", name); put("arguments", arguments)
    }

    private class QueryState {
        val access = CodeModeRegistryAccess(ReentrantLock(), CodeModeKeyLocks())
        val call = CodeModePending("runtime", "callback", "mcp__capture__inspect", JsonObject(emptyMap()), true)
        val record = CodeModeRecord(
            "record",
            "key",
            CodeModePhase.ACTIVE,
            origin = CodeModeOrigin(
                JsonObject(emptyMap()),
                "outer",
                "source",
                CodeModeBaseline(0, "", 0, "", 1),
            ),
            progress = CodeModeProgress(updatedAt = 0, lastDigest = "digest"),
            carry = CodeModeNativeContinuity(emptyList(), emptyList(), emptyList()),
        )
        val records = mutableListOf(record)
        private val queries = CodeModeRecordQueries(
            access,
            records,
            CodeModeExpiredHistory(mutableListOf(), null),
        ) { }

        init {
            access.withKey("key") { record.sessionId = "session"; record.progress.pending += call }
        }

        fun snapshot(session: String = "session"): Map<String, CodeModeMcpOwner> =
            queries.mcpOwnership.snapshot("key", session, setOf("callback"))

        fun accept(result: CodeModeResult) {
            access.withKey("key") { record.accepted.accept(mapOf(result.id to result), emptyMap()) }
        }
    }
}
