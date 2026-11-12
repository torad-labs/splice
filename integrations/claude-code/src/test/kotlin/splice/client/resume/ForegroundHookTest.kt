// NEW: user hooks keep their order through a head's settings, and the retired foreground hook's entries are stripped.
package splice.client.resume

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.client.ClaudeConfigMaterializer
import splice.client.ClaudePolicy
import splice.client.HookSettings
import splice.client.MaterializeSignIn
import splice.client.MaterializeSpec
import java.nio.file.Files
import java.nio.file.Path

class ForegroundHookTest {
    @Test
    fun `an interrupted settings swap chooses the matching hook origins`() {
        val before = Json.parseToJsonElement("""{"PreToolUse":["local","old"]}""")
        val after = Json.parseToJsonElement("""{"PreToolUse":["local","new"]}""")
        val old = Json.parseToJsonElement("""{"PreToolUse":["old"]}""")
        val next = Json.parseToJsonElement("""{"PreToolUse":["new"]}""")
        val memo = HookSettings.origins(old, before, next, after)
        assertEquals(old, HookSettings.inherited(memo, before))
        assertEquals(next, HookSettings.inherited(memo, after))
    }

    private fun userHooks(vararg commands: String): JsonObject = buildJsonObject {
        put(
            "PreToolUse",
            JsonArray(
                commands.map { command ->
                    buildJsonObject {
                        put(
                            "hooks",
                            JsonArray(
                                listOf(
                                    buildJsonObject {
                                        put("type", "command")
                                        put("command", command)
                                    },
                                ),
                            ),
                        )
                    }
                },
            ),
        )
    }

    private fun hookCommands(hooks: JsonObject?): List<String> =
        hooks?.get("PreToolUse")?.jsonArray.orEmpty().map { entry ->
            entry.jsonObject["hooks"]!!.jsonArray.single().jsonObject["command"]!!.jsonPrimitive.content
        }

    private fun interruptedMemo(dir: Path): JsonObject {
        val before = HookSettings.merge(userHooks("global-old"), userHooks("local-one", "local-two"), emptyMap(), dir)
        val after = HookSettings.merge(userHooks("global-new"), before.hooks, emptyMap(), dir, before.inherited)
        return HookSettings.origins(before.inherited, before.hooks, after.inherited, after.hooks)
    }

    private fun retryCommands(memo: JsonObject, local: JsonObject, global: JsonObject, dir: Path): List<String> {
        val inherited = HookSettings.inherited(memo, local)
        return hookCommands(HookSettings.merge(global, local, emptyMap(), dir, inherited).hooks)
    }

    @Test
    fun `an interrupted origin memo followed by a local edit removes the obsolete global hook`(@TempDir dir: Path) {
        val memo = interruptedMemo(dir)
        val local = userHooks("local-one", "local-two", "global-old", "local-added")
        val result = retryCommands(memo, local, userHooks("global-new"), dir)
        assertEquals(listOf("local-one", "local-two", "local-added", "global-new"), result)
        assertEquals(4, result.size)
    }

    @Test
    fun `an interrupted origin memo with no local edit selects the old inherited occurrences`(@TempDir dir: Path) {
        val memo = interruptedMemo(dir)
        val local = userHooks("local-one", "local-two", "global-old")
        assertEquals(
            listOf("local-one", "local-two", "global-new"),
            retryCommands(memo, local, userHooks("global-new"), dir),
        )
    }

    @Test
    fun `a completed swap followed by a local edit and global change selects the new origins`(@TempDir dir: Path) {
        val memo = interruptedMemo(dir)
        val local = userHooks("local-one", "local-two", "global-new", "local-added")
        assertEquals(
            listOf("local-one", "local-two", "local-added", "global-next"),
            retryCommands(memo, local, userHooks("global-next"), dir),
        )
    }

    @Test
    fun `a deliberate local copy survives while its hook remains global and after its later removal`(
        @TempDir dir: Path,
    ) {
        val memo = interruptedMemo(dir)
        val local = userHooks("local-one", "local-two", "global-new", "global-new")
        val inherited = HookSettings.inherited(memo, local)
        val merged = HookSettings.merge(userHooks("global-new"), local, emptyMap(), dir, inherited)
        assertEquals(listOf("local-one", "local-two", "global-new"), hookCommands(merged.hooks))
        val nextMemo = HookSettings.origins(inherited, local, merged.inherited, merged.hooks)
        assertEquals(
            listOf("local-one", "local-two", "global-new"),
            retryCommands(nextMemo, requireNotNull(merged.hooks), userHooks(), dir),
        )
    }

    @Test
    fun `both inherited snapshots present prefer completed origins and preserve the old local copy`(
        @TempDir dir: Path,
    ) {
        val memo = interruptedMemo(dir)
        val local = userHooks("local-one", "local-two", "global-new", "global-old", "local-added")
        assertEquals(
            listOf("local-one", "local-two", "global-old", "local-added", "global-next"),
            retryCommands(memo, local, userHooks("global-next"), dir),
        )
    }

    @Test
    fun `first launch then partial local deletion and global removal leave only user hooks`(@TempDir dir: Path) {
        val original = userHooks("local-one", "local-two")
        val first = HookSettings.merge(userHooks("global-one", "global-two"), original, emptyMap(), dir)
        val memo = HookSettings.origins(null, original, first.inherited, first.hooks)
        val local = userHooks("local-one", "local-two", "global-one")
        assertEquals(listOf("local-one", "local-two"), retryCommands(memo, local, userHooks(), dir))
    }

    @Test
    fun `a surviving new occurrence proves a completed swap despite old local copies`(@TempDir dir: Path) {
        val before = HookSettings.merge(userHooks("old-one", "old-two"), userHooks("local-one"), emptyMap(), dir)
        val after = HookSettings.merge(userHooks("new-one", "new-two"), before.hooks, emptyMap(), dir, before.inherited)
        val memo = HookSettings.origins(before.inherited, before.hooks, after.inherited, after.hooks)
        val local = userHooks("local-one", "new-one", "old-one", "old-two")
        assertEquals(
            listOf("local-one", "old-one", "old-two", "global-next"),
            retryCommands(memo, local, userHooks("global-next"), dir),
        )
    }

    @Test
    fun `a global hook replacement never leaves the old inherited command running`(@TempDir home: Path) {
        val global = Files.createDirectories(home.resolve(".claude"))
        val head = Files.createDirectories(home.resolve(".claude-synthetic"))
        val first = """{"hooks":{"PreToolUse":[{"hooks":[{"type":"command","command":"global-old"}]}]}}"""
        val next = """{"hooks":{"PreToolUse":[{"hooks":[{"type":"command","command":"global-new"}]}]}}"""
        Files.writeString(global.resolve("settings.json"), first)
        val materializer = ClaudeConfigMaterializer(home)
        val spec = MaterializeSpec(
            configDir = head,
            policy = ClaudePolicy(share = setOf("settings"), isolate = emptySet()),
            availableModelIds = listOf("synthetic-model"),
            defaultModel = "synthetic-model",
            modelOptionsCache = buildJsonObject { },
            statuslineCommand = "",
            signIn = MaterializeSignIn(
                headKey = "synthetic",
            ),
        )
        materializer.materialize(spec)
        Files.writeString(global.resolve("settings.json"), next)
        materializer.materialize(spec)
        val hooks = Json.parseToJsonElement(Files.readString(head.resolve("settings.json"))).jsonObject["hooks"]
        assertEquals(Json.parseToJsonElement(next).jsonObject["hooks"], hooks)
    }

    @Test
    fun `a local hook identical to an inherited hook remains local after global replacement`(@TempDir home: Path) {
        val global = Files.createDirectories(home.resolve(".claude"))
        val head = Files.createDirectories(home.resolve(".claude-synthetic"))
        val first = """{"hooks":{"PreToolUse":[{"hooks":[{"type":"command","command":"shared-user"}]}]}}"""
        val inherited = """{"hooks":{"PreToolUse":[{"hooks":[{"type":"command","command":"shared-user"}]},""" +
            """{"hooks":[{"type":"command","command":"global-old"}]}]}}"""
        val next = """{"hooks":{"PreToolUse":[{"hooks":[{"type":"command","command":"global-new"}]}]}}"""
        Files.writeString(global.resolve("settings.json"), inherited)
        Files.writeString(head.resolve("settings.json"), first)
        val materializer = ClaudeConfigMaterializer(home)
        val spec = MaterializeSpec(
            configDir = head,
            policy = ClaudePolicy(share = setOf("settings"), isolate = emptySet()),
            availableModelIds = listOf("synthetic-model"),
            defaultModel = "synthetic-model",
            modelOptionsCache = buildJsonObject { },
            statuslineCommand = "",
            signIn = MaterializeSignIn(
                headKey = "synthetic",
            ),
        )
        materializer.materialize(spec)
        Files.writeString(global.resolve("settings.json"), next)
        materializer.materialize(spec)
        val hooks = Json.parseToJsonElement(Files.readString(head.resolve("settings.json"))).jsonObject["hooks"]!!
        val expected = Json.parseToJsonElement(first).jsonObject["hooks"]!!.jsonObject["PreToolUse"]!!.jsonArray +
            Json.parseToJsonElement(next).jsonObject["hooks"]!!.jsonObject["PreToolUse"]!!.jsonArray
        assertEquals(expected, hooks.jsonObject["PreToolUse"]!!.jsonArray.toList())
    }

    @Test
    fun `materialization keeps user hooks in order and strips the retired foreground hook an earlier install wrote`(
        @TempDir home: Path,
    ) {
        val global = Files.createDirectories(home.resolve(".claude"))
        val head = Files.createDirectories(home.resolve(".claude-synthetic"))
        val retired = head.resolve(ForegroundHook.SCRIPT)
        val globalHooks = """{"hooks":{"PreToolUse":[{"matcher":"Read","hooks":[""" +
            """{"type":"command","command":"user-global-one"},""" +
            """{"type":"command","command":"user-global-two"}]}]}}"""
        val localHooks = """{"hooks":{"PreToolUse":[{"matcher":"Write","hooks":[""" +
            """{"type":"command","command":"user-local-one"},""" +
            """{"type":"command","command":"user-local-two"}]}],""" +
            """"PostToolUse":[{"hooks":[{"type":"command","command":"splice-foreground-hook.sh.user"}]},""" +
            """{"hooks":[{"type":"command","command":"$retired","async":true,"timeout":2}]}]}}"""
        Files.writeString(global.resolve("settings.json"), globalHooks)
        Files.writeString(head.resolve("settings.json"), localHooks)
        val materializer =
            ClaudeConfigMaterializer(home, resumeHook = ResumeHookTarget(3096) { home.resolve("synthetic-header") })
        val spec = MaterializeSpec(
            configDir = head,
            policy = ClaudePolicy(share = setOf("settings"), isolate = emptySet()),
            availableModelIds = listOf("synthetic-model"),
            defaultModel = "synthetic-model",
            modelOptionsCache = buildJsonObject { },
            statuslineCommand = "",
            signIn = MaterializeSignIn(
                headKey = "synthetic",
            ),
        )
        materializer.materialize(spec)
        val first = Json.parseToJsonElement(
            Files.readString(head.resolve("settings.json")),
        ).jsonObject["hooks"]!!.jsonObject
        val localPre = Json.parseToJsonElement(
            localHooks,
        ).jsonObject["hooks"]!!.jsonObject["PreToolUse"]!!.jsonArray.single()
        val globalPre = Json.parseToJsonElement(
            globalHooks,
        ).jsonObject["hooks"]!!.jsonObject["PreToolUse"]!!.jsonArray.single()
        assertEquals(listOf(localPre, globalPre), first["PreToolUse"]!!.jsonArray.toList())
        assertEquals(
            Json.parseToJsonElement(localHooks).jsonObject["hooks"]!!.jsonObject["PostToolUse"]!!.jsonArray.first(),
            first["PostToolUse"]!!.jsonArray.single(),
            "the user's own PostToolUse hook stays and the retired foreground entry is gone",
        )
        assertEquals(null, first["SessionEnd"], "no head registers a foreground callback on session end")
        materializer.materialize(spec)
        val second = Json.parseToJsonElement(Files.readString(head.resolve("settings.json"))).jsonObject["hooks"]
        assertEquals(first, second, "generated callbacks are replaced in place, never duplicated")
    }
}
