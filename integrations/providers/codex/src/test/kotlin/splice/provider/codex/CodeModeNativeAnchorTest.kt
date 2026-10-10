// NEW: native replay survives canonical-to-client offset changes without accepting edited payloads.
package splice.provider.codex

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.provider.codex.state.CodeModeNativeChain
import splice.provider.codex.state.CodeModeStateJournal
import splice.provider.codex.state.diagnostics.CodeModeHistoryLog
import splice.provider.codex.state.diagnostics.CodeModeNativeBranch
import splice.upstream.RoundBody
import splice.upstream.codemode.CodeModeResult
import java.nio.file.Files
import java.nio.file.Path

internal class CodeModeNativeAnchorTest {
    private val history = CodexCodeModeHistory(Json)
    private val first = item("""{"role":"user","content":"first synthetic request"}""")
    private val latest = item("""{"role":"user","content":"next synthetic request"}""")
    private val native = item("""{"type":"reasoning","id":"reason-between","encrypted_content":"synthetic"}""")
    private val preface = item("""{"role":"assistant","phase":"commentary","content":"Synthetic preface"}""")

    @Test
    fun `a missing older opaque anchor does not abandon unchanged native client history`() {
        val baseline = listOf(first, outer("old"), output("old"), native, latest)
        val active = record(baseline, emptyList(), "active")
        val client = listOf(first) + callbacks("old", 2) + native + latest + callbacks("active", 1)

        val restored = history.restoreBaseline(body(client), active)

        assertNull(restored.error)
        assertEquals(1, input(checkNotNull(restored.bodyJson)).count { it == native })
    }

    @Test
    fun `duplicated canonical prose does not shift a later unchanged native out of its claim`() {
        val old = record(listOf(first), emptyList(), "old").apply {
            phase = CodeModePhase.COMPLETED
            carry.continuity = listOf(preface)
            progress.output = "done"
        }
        val baseline = listOf(first, preface, outer("old"), output("old"), preface, native, latest)
        val active = record(baseline, listOf(old), "active")
        val client = listOf(first, preface) + callbacks("old", 2) + native + latest + callbacks("active", 1)

        val restored = history.restoreBaseline(body(client), active)

        assertNull(restored.error)
        assertEquals(1, input(checkNotNull(restored.bodyJson)).count { it == native })
    }

    @Test
    fun `an edited native payload with a missing older anchor is still rejected`() {
        val baseline = listOf(first, outer("old"), output("old"), native, latest)
        val active = record(baseline, emptyList(), "active")
        val edited = item("""{"type":"reasoning","id":"reason-between","encrypted_content":"edited synthetic"}""")
        val client = listOf(first) + callbacks("old", 2) + edited + latest + callbacks("active", 1)

        val restored = history.restoreBaseline(body(client), active)
        assertEquals("code-mode native discovery history was edited: payload", restored.error)
        assertEquals("native_following=present native_branch=payload", restored.nativeRejection?.logFields())
    }

    @Test
    fun `a native moved away from an intact adjacent anchor is still rejected`() {
        val baseline = listOf(first, native, latest)
        val active = record(baseline, emptyList(), "active")
        val client = listOf(native, first, latest) + callbacks("active", 1)

        val restored = history.restoreBaseline(body(client), active)
        assertEquals(
            "code-mode native discovery history conflicts with its captured position: unexpected-offset",
            restored.error,
        )
        assertEquals("native_following=present native_branch=unexpected-offset", restored.nativeRejection?.logFields())
        assertTrue(active.replayAnchors?.native?.values?.all { it.logicalTail == 0 } == true)
    }

    @Test
    fun `an absent native with a retired prior anchor restores before its following stable item`() {
        val baseline = listOf(first, outer("old"), output("old"), native, latest)
        val active = record(baseline, emptyList(), "active")
        val client = listOf(first) + callbacks("old", 2) + latest + callbacks("active", 1)

        val restored = history.restoreBaseline(body(client), active)

        assertNull(restored.error)
        val items = input(checkNotNull(restored.bodyJson))
        assertEquals(items.indexOf(latest) - 1, items.indexOf(native))
    }

    @Test
    fun `counted native witnesses retain repeats and reject extra or reordered occurrences`() {
        val middle = item("""{"role":"user","content":"middle synthetic request"}""")
        val baseline = listOf(first, outer("old"), output("old"), native, middle) +
            listOf(outer("older"), output("older"), native, latest)
        val active = record(baseline, emptyList(), "active")
        val prefix = listOf(first) + callbacks("old", 2) + native + middle + callbacks("older", 2)
        val client = prefix + native + latest + callbacks("active", 1)
        val restored = history.restoreBaseline(body(client), active)
        assertNull(restored.error)
        assertEquals(2, input(checkNotNull(restored.bodyJson)).count { it == native })

        val extra = prefix + native + middle + native + latest + callbacks("active", 1)
        val extraError = history.restoreBaseline(body(extra), active).error
        assertEquals("code-mode native discovery history was edited: nativeOrder", extraError)

        val other = item("""{"type":"reasoning","id":"reason-other","encrypted_content":"other synthetic"}""")
        val distinct = baseline.toMutableList().apply { this[7] = other }
        val separate = record(distinct, emptyList(), "active")
        val swapped = listOf(first) + callbacks("old", 2) + other + middle + callbacks("older", 2) +
            native + latest + callbacks("active", 1)
        val reorderedError = history.restoreBaseline(body(swapped), separate).error
        assertEquals("code-mode native discovery history was edited: nativeOrder", reorderedError)
    }

    @Test
    fun `a later repeated native is counted inside its own stable anchor bounds`() {
        val middle = item("""{"role":"user","content":"middle synthetic request"}""")
        val old = record(listOf(first, native, middle), emptyList(), "old").apply {
            phase = CodeModePhase.COMPLETED
            progress.output = "done"
        }
        val baseline = listOf(first, native, middle, outer("old"), output("old"), native, latest)
        val active = record(baseline, listOf(old), "active")
        val client = listOf(first, native, middle) + callbacks("old", 2) + native + latest + callbacks("active", 1)

        val restored = history.restoreBaseline(body(client), active)

        assertNull(restored.error)
        assertEquals(2, input(checkNotNull(restored.bodyJson)).count { it == native })
    }

    @Test
    fun `an inherited native survives a child repeat beyond the parent callback bound`() {
        val middle = item("""{"role":"user","content":"middle synthetic request"}""")
        val parentBaseline = listOf(first, outer("retired"), output("retired"), native, middle)
        val parent = record(parentBaseline, emptyList(), "parent").apply {
            phase = CodeModePhase.COMPLETED
            progress.output = "done"
        }
        val baseline = parentBaseline + listOf(outer("parent"), output("parent"), native, latest)
        val active = record(baseline, listOf(parent), "active")
        parent.replayAnchors = parent.replayAnchors?.copy(nativeFollowing = emptyMap())
        active.replayAnchors = active.replayAnchors?.copy(nativeFollowing = emptyMap())
        val client = listOf(first) + callbacks("retired", 2) + native + middle +
            callbacks("parent", 1) + native + latest + callbacks("active", 1)
        val request = body(client)
        assertNull(history.restoreBaseline(request, active).error, "the flat capture is the same history")

        val capture = CodeModeNativeChain.capture(active.carry.segments, parent)
        assertEquals(parent, capture.parent)
        assertEquals(listOf(6), capture.segments.map { it.logicalOffset })
        active.carry.segments = capture.segments
        active.nativeBaseId = parent.id
        active.nativeParent = parent

        val restored = history.restoreBaseline(request, active)
        assertNull(restored.error)
        assertEquals(client, input(checkNotNull(restored.bodyJson)))

        val missingParent = listOf(first) + callbacks("retired", 2) + callbacks("parent", 1) +
            latest + callbacks("active", 1)
        val rejected = history.restoreBaseline(body(missingParent), active)
        assertEquals("code-mode native discovery history could not be placed: absent", rejected.error)
        assertEquals("native_following=absent native_branch=absent", rejected.nativeRejection?.logFields())
    }

    @Test
    fun `an absent native restores before the first of multiple following stable items`() {
        val middle = item("""{"role":"user","content":"middle synthetic request"}""")
        val baseline = listOf(first, outer("old"), output("old"), native, middle, latest)
        val active = record(baseline, emptyList(), "active")
        val prefix = listOf(first) + callbacks("old", 2)
        val suffix = listOf(middle, latest) + callbacks("active", 1)
        assertNull(history.restoreBaseline(body(prefix + native + suffix), active).error)

        val restored = history.restoreBaseline(body(prefix + suffix), active)
        assertNull(restored.error)
        assertEquals(prefix + native + suffix, input(checkNotNull(restored.bodyJson)))
    }

    @Test
    fun `an absent native is not guessed when the client also removed its immediate following witness`() {
        val middle = item("""{"role":"user","content":"middle synthetic request"}""")
        val baseline = listOf(first, outer("old"), output("old"), native, middle, latest)
        val active = record(baseline, emptyList(), "active")
        val prefix = listOf(first) + callbacks("old", 2)
        val suffix = listOf(latest) + callbacks("active", 1)
        assertNull(history.restoreBaseline(body(prefix + native + suffix), active).error)

        assertEquals(
            "code-mode native discovery history could not be placed: absent",
            history.restoreBaseline(body(prefix + suffix), active).error,
            "the surviving later item must not substitute for the native's missing immediate witness",
        )
    }

    @Test
    fun `a following witness still rejects edited or moved native payloads`() {
        val middle = item("""{"role":"user","content":"middle synthetic request"}""")
        val baseline = listOf(first, outer("old"), output("old"), native, middle, latest)
        val active = record(baseline, emptyList(), "active")
        val prefix = listOf(first) + callbacks("old", 2)
        val edited = item("""{"type":"reasoning","id":"reason-between","encrypted_content":"edited synthetic"}""")
        val corrupted = listOf(
            prefix + edited + middle + latest + callbacks("active", 1),
            prefix + middle + native + latest + callbacks("active", 1),
        )
        val reasons = listOf(
            "code-mode native discovery history was edited: payload",
            "code-mode native discovery history conflicts with its captured position: unexpected-offset",
        )
        for ((at, client) in corrupted.withIndex()) {
            assertEquals(reasons[at], history.restoreBaseline(body(client), active).error)
        }
    }

    @Test
    fun `an owned opaque successor witnesses an absent native across an older callback expansion`(@TempDir dir: Path) {
        val retired = record(listOf(first), emptyList(), "retired").apply {
            phase = CodeModePhase.COMPLETED
            progress.output = "done"
        }
        val baseline = listOf(first, outer("retired"), output("retired"), native, outer("old"), output("old"), latest)
        val old = record(baseline.take(4), listOf(retired), "old").apply {
            phase = CodeModePhase.COMPLETED
            progress.output = "done"
        }
        val active = record(baseline, listOf(retired, old), "active")
        val prefix = listOf(first) + callbacks("retired", 2)
        val suffix = listOf(outer("old"), output("old"), latest) + callbacks("active", 1)

        for (owner in listOf(active, active.snapshot().restore())) {
            val restored = history.restoreBaseline(body(prefix + suffix), owner)
            assertNull(restored.error)
            assertEquals(prefix + native + suffix, input(checkNotNull(restored.bodyJson)))
            assertEquals(
                "code-mode native discovery history could not be placed: absent",
                history.restoreBaseline(body(prefix + listOf(latest) + callbacks("active", 1)), owner).error,
                "a later stable item cannot replace the missing owned successor",
            )
            val invalid = listOf(
                listOf(outer("wrong"), output("old"), latest),
                listOf(output("old"), latest),
                listOf(outer("old"), outer("old"), output("old"), latest),
            )
            invalid.forEach { changed ->
                assertEquals(
                    "code-mode native discovery history could not be placed: absent",
                    history.restoreBaseline(body(prefix + changed + callbacks("active", 1)), owner).error,
                    "wrong kind, wrong identity, and duplicated identities prove no adjacent witness",
                )
            }
        }
        val saved = active.snapshot().apply { replayAnchors = replayAnchors?.copy(nativeFollowing = emptyMap()) }
        val file = dir.resolve("owned-legacy.jsonl")
        Files.writeString(file, Json.encodeToString(CodeModePersistedState(records = listOf(saved))) + "\n")
        val legacy = CodeModeStateJournal.read(file, Json).records.single().restore()
        assertTrue(legacy.replayAnchors?.nativeFollowing?.isEmpty() == true)
        assertNull(history.restoreBaseline(body(prefix + native + suffix), legacy).error)
    }

    @Test
    fun `an owned successor places native history after the real next turn rewrite`() {
        val retired = record(listOf(first), emptyList(), "retired").apply {
            phase = CodeModePhase.COMPLETED
            progress.output = "done"
        }
        retired.accepted.accept(
            (0 until 2).associate { "callback-retired-$it".let { id -> id to CodeModeResult(id, "synthetic result") } },
            emptyMap(),
        )
        val oldBaseline = history.canonicalize(body(listOf(first) + callbacks("retired", 2)), listOf(retired))
        val old = record(input(checkNotNull(oldBaseline.bodyJson)), listOf(retired), "old").apply {
            phase = CodeModePhase.COMPLETED
            progress.output = "done"
            carry.replay = listOf(CodeModeNativeSegment(0, listOf(native)))
        }
        old.accepted.accept(mapOf("callback-old-0" to CodeModeResult("callback-old-0", "synthetic result")), emptyMap())
        val completed = listOf(retired, old)
        val raw = listOf(first) + callbacks("retired", 2) + callbacks("old", 1) + latest
        val capture = history.canonicalize(body(raw), completed)
        assertTrue(capture.omitted.isEmpty())
        val active = record(input(checkNotNull(capture.bodyJson)), completed, "active")
        old.origin.source = "return 'changed synthetic source';"
        old.origin.outer = JsonObject(old.origin.outer + ("input" to item("\"changed synthetic source\"")))
        val next = history.canonicalize(body(raw + native + callbacks("active", 1)), completed)
        assertTrue(next.omitted.isEmpty())

        for (owner in listOf(active, active.snapshot().restore())) {
            val restored = history.restoreBaseline(checkNotNull(next.body), owner)
            assertNull(restored.error)
            assertEquals(input(checkNotNull(next.bodyJson)), input(checkNotNull(restored.bodyJson)))
        }
    }

    @Test
    fun `legacy journals without following metadata load empty and retain their native history`(@TempDir dir: Path) {
        val baseline = listOf(first, outer("old"), output("old"), native, latest)
        val active = record(baseline, emptyList(), "active")
        val encoded = Json.encodeToString(CodeModePersistedState(records = listOf(active.snapshot())))
        val root = Json.parseToJsonElement(encoded).jsonObject
        val rows = root.getValue("records").jsonArray.map { raw ->
            val row = raw.jsonObject
            val anchors = row.getValue("replayAnchors").jsonObject
            assertTrue(anchors.getValue("nativeFollowing").jsonObject.isNotEmpty())
            JsonObject(row + ("replayAnchors" to JsonObject(anchors - "nativeFollowing")))
        }
        val file = dir.resolve("legacy.jsonl")
        Files.writeString(file, JsonObject(root + ("records" to JsonArray(rows))).toString() + "\n")

        val restored = CodeModeStateJournal.read(file, Json).records.single().restore()

        assertTrue(restored.replayAnchors?.nativeFollowing?.isEmpty() == true)
        val client = listOf(first) + callbacks("old", 2) + native + latest + callbacks("active", 1)
        val placed = history.restoreBaseline(body(client), restored)
        assertNull(placed.error)
        assertEquals(client, input(checkNotNull(placed.bodyJson)))
    }

    @Test
    fun `an uncaptured native does not borrow the witness of an allowed item at its offset`() {
        val owner = record(listOf(first, native, latest), emptyList(), "active")
        val uncaptured = item("""{"type":"reasoning","id":"uncaptured","encrypted_content":"synthetic extra"}""")
        val client = listOf(first, native, uncaptured, latest) + callbacks("active", 1)

        val restored = history.restoreBaseline(body(client), owner)

        assertEquals(
            "code-mode native discovery history conflicts with its captured position: unexpected-offset",
            restored.error,
        )
        assertEquals("native_following=unknown native_branch=unexpected-offset", restored.nativeRejection?.logFields())
    }

    @Test
    fun `native rejection logs identify the session witness and actual placement branch`(@TempDir dir: Path) {
        for (branch in listOf("absent", "nativeOrder")) {
            for (following in listOf(false, true)) {
                logRejection(dir.resolve("$branch-$following"), branch, following)
            }
        }
    }

    private fun logRejection(dir: Path, branch: String, following: Boolean) {
        val middle = item("""{"role":"user","content":"middle synthetic request"}""")
        val baseline = listOf(first, outer("retired"), output("retired"), native, middle) +
            if (branch == "nativeOrder") listOf(outer("older"), output("older"), native, latest) else listOf(latest)
        val owner = record(baseline, emptyList(), "active").apply {
            sessionId = "synthetic-session-hidden"
            if (!following) replayAnchors = replayAnchors?.copy(nativeFollowing = emptyMap())
        }
        val repetitions = if (branch == "nativeOrder") listOf(native, native, native) else emptyList()
        val client = listOf(first) + callbacks("retired", 2) + repetitions + latest + callbacks("active", 1)
        val request = body(client)
        val restored = history.restoreBaseline(request, owner)
        val error = checkNotNull(restored.error)
        val expectedBranch = if (branch == "absent") CodeModeNativeBranch.ABSENT else CodeModeNativeBranch.NATIVE_ORDER
        assertEquals(expectedBranch.reason(), error)
        val lines = mutableListOf<String>()
        CodexCodeModeWire(Json, { lines += it }).canonicalize(request, listOf(owner))
        val prefix = "session syntheti native_following=${if (following) "present" else "absent"} native_branch=$branch"
        val marker = CodeModeHistoryLog.context(owner, restored.nativeRejection)
        assertTrue(marker.startsWith(prefix))
        assertEquals(
            "[code-mode] history rewrite skipped record active (outer active): $error; " +
                "its client calls stay in the history as ordinary tool calls; $marker",
            lines.single(),
        )
        abandonedLog(dir, owner, restored, lines, marker)
    }

    private fun abandonedLog(
        dir: Path,
        owner: CodeModeRecord,
        restored: CodeModeRewrite,
        lines: MutableList<String>,
        marker: String,
    ) {
        val error = checkNotNull(restored.error)
        val bridge = CodexCodeModeBridge(
            CodeModeBridgeConfig(
                runtimes = { error("this log control never opens a runtime") },
                state = CodeModeStateLocation(dir.resolve("records"), dir.resolve("legacy.json")),
                log = { lines += it },
            ),
        )
        try {
            val registry = privateField(bridge, "registry") as CodexCodeModeRegistry
            assertTrue(registry.add(owner))
            val controller = privateField(bridge, "controller")
            val abandon = controller.javaClass.declaredMethods.single { it.name == "abandon" }
                .apply { isAccessible = true }
            val rejection = restored.javaClass.methods.firstOrNull { it.name == "getNativeRejection" }?.invoke(restored)
            val arguments = if (abandon.parameterCount == 3) arrayOf(owner, error, rejection) else arrayOf(owner, error)
            abandon.invoke(controller, *arguments)
            assertEquals(
                "[code-mode] abandoned record active (outer active): $error; " +
                    "continuing upstream on the client's history; $marker",
                lines.last(),
            )
            assertFalse(lines.any { "synthetic-session-hidden" in it || "reason-between" in it })
            assertEquals("$CODE_MODE_ABANDONED: $error; source was not rerun", owner.error)
            val saved = Json.encodeToString(CodeModePersistedState(records = listOf(owner.snapshot())))
            assertFalse("nativeRejection" in saved || "native_branch" in saved)
        } finally {
            bridge.onHeadStop()
        }
    }

    private fun <O : Any> privateField(owner: O, name: String): Any =
        owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)

    private fun record(items: List<JsonElement>, completed: List<CodeModeRecord>, id: String): CodeModeRecord {
        val baseline = checkNotNull(history.anchoredBoundary(body(items), completed))
        return CodeModeRecord(
            id = id,
            key = "synthetic-conversation",
            phase = CodeModePhase.ACTIVE,
            origin = CodeModeOrigin(
                outer = outer(id),
                outerCallId = id,
                source = "return 'synthetic';",
                baseline = CodeModeBaseline(
                    inputCount = baseline.fullCount,
                    inputDigest = baseline.fullDigest,
                    logicalCount = baseline.logicalCount,
                    logicalDigest = baseline.logicalDigest,
                    metadataVersion = CODE_MODE_METADATA_VERSION,
                ),
            ),
            progress = CodeModeProgress(updatedAt = 0, lastDigest = "synthetic-request"),
            carry = CodeModeNativeContinuity(baseline.nativeSegments, emptyList(), emptyList()),
        ).also {
            it.replayAnchors = baseline.replayAnchors
            it.progress.pending +=
                CodeModePending("runtime-$id", "callback-$id-0", "Read", JsonObject(emptyMap()), true)
        }
    }

    private fun callbacks(id: String, count: Int): List<JsonElement> = (0 until count).flatMap { at ->
        listOf(
            item("""{"type":"function_call","call_id":"callback-$id-$at","name":"Read","arguments":"{}"}"""),
            item("""{"type":"function_call_output","call_id":"callback-$id-$at","output":"synthetic result"}"""),
        )
    }

    private fun outer(id: String): JsonObject = item(
        """{"type":"custom_tool_call","call_id":"$id","name":"exec","input":"return 'synthetic';"}""",
    ) as JsonObject

    private fun output(id: String): JsonElement =
        item("""{"type":"custom_tool_call_output","call_id":"$id","output":"done"}""")

    private fun body(items: List<JsonElement>): CodeModeBody =
        CodeModeBody(RoundBody.Tree(JsonObject(mapOf("input" to JsonArray(items)))), Json)

    private fun input(text: String): List<JsonElement> =
        ((Json.parseToJsonElement(text) as JsonObject)["input"] as JsonArray).toList()

    private fun item(text: String): JsonElement = Json.parseToJsonElement(text)
}
