// NEW: unit-level proof for the safe-by-default launch recipe (OSS-B). LaunchService.launch must
// never add --dangerously-skip-permissions unless the caller explicitly opts in via
// dangerouslySkipPermissions=true — and doing so must surface a non-null warning, never silently.
// LaunchSpec construction mirrors ControlServerTest.kt/WebuiContractTest.kt in this same package.
package splice.control

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.launch.ClaudeConfigMaterializer
import splice.core.launch.ClaudePolicy
import splice.core.launch.SessionOwnership
import java.nio.file.Files
import java.nio.file.Path

class LaunchServiceTest {

    private val tmp = Files.createTempDirectory("launch-service-test")
    private val service = LaunchService(ClaudeConfigMaterializer(tmp))

    private fun spec(
        head: String,
        pinned: String = "gpt-5.6-sol",
        available: List<String> = listOf("gpt-5.6-sol", "gpt-5.4-mini"),
        labels: Map<String, String> = available.associateWith { it },
    ) = LaunchSpec(
        trees = HeadTrees(tmp.resolve(".claude-$head")),
        pinnedModel = pinned,
        availableModelIds = available,
        modelLabels = labels,
        contextWindow = 272000,
        modelOptionsCache = kotlinx.serialization.json.buildJsonObject { },
        statuslineCommand = "\"/bin/curl\" -s :3096/statusline",
        loginCommand = "claudex login",
        signInLabel = "Codex (ChatGPT)",
        policy = ClaudePolicy(share = emptySet(), isolate = emptySet()),
        port = 3099,
        inferenceToken = "test-inference-token",
        apiTimeoutMs = 960_000,
    )

    // 2026-09-01: every claudex compaction ran 500-580s against Claude Code's 600s default request
    // timeout while the daemon's whole-turn cap was 900s — the two over ten minutes died as
    // client_abort with the summary still streaming. The recipe plants API_TIMEOUT_MS from the
    // head's own budget so the client outlives the proxy's wall and receives its honest verdict.
    @Test
    fun `API_TIMEOUT_MS is planted from the head's whole-turn budget`() {
        val env = service.launch(spec("codex"), emptyList(), dangerouslySkipPermissions = false).env
        assertEquals("960000", env["API_TIMEOUT_MS"])
    }

    // V4-72: the client's own retry budget is 10 attempts (~2-3 min), so a rate-limit hold longer
    // than that used to end the SESSION rather than resume when the window reopened. Persistent mode
    // is a client env flag (CLAUDE_CODE_RETRY_WATCHDOG, read by QI() in the 2.1.257 binary) and it
    // is planted for EVERY head, so both spellings are pinned: a foreign head, and the native one
    // that runs its client in subscriber mode — the mode whose 429 gate persistent retry is checked
    // BEFORE, which is exactly why the native head needs this too rather than being exempt.
    @Test
    fun `CLAUDE_CODE_RETRY_WATCHDOG is planted for a foreign head`() {
        val env = service.launch(spec("codex"), emptyList(), dangerouslySkipPermissions = false).env
        assertEquals("1", env["CLAUDE_CODE_RETRY_WATCHDOG"])
    }

    @Test
    fun `CLAUDE_CODE_RETRY_WATCHDOG is planted for the native head too - V4-72`() {
        val env = service.launch(nativeSpec(), emptyList(), dangerouslySkipPermissions = false).env
        assertEquals(
            "1",
            env["CLAUDE_CODE_RETRY_WATCHDOG"],
            "a native head's client runs in subscriber mode; persistent retry is checked before that gate",
        )
    }

    // DR-81 (assembly sweep): the spec is assembled once at boot, but `splice key set` promises
    // live pickup — the capture hook and advertiser used to be frozen at the boot-time key check,
    // so every later launch still armed the paste-your-key hook against a working credential
    // (the review-of-#75 overwrite risk) and a key unset never re-armed setup. Key presence is a
    // LAUNCH-time input now; the spec carries the ungated capability.
    @Test
    fun `a present key disarms token capture and the advertiser at launch time - DR-81`() {
        val armed = spec("cap").copy(
            tokenCapture = splice.core.launch.TokenCaptureSpec("K_ENV", "sk-or-[A-Za-z0-9_-]{20,}", "OpenRouter"),
            advertiseKeySetup = true,
        )
        service.launch(armed, emptyList(), dangerouslySkipPermissions = false, keyPresentNow = true)
        assertFalse(treeContains(tmp.resolve(".claude-cap"), "sk-or-"), "capture hook must be disarmed")
    }

    @Test
    fun `an absent key arms token capture at launch time - DR-81 control`() {
        val armed = spec("cap2").copy(
            tokenCapture = splice.core.launch.TokenCaptureSpec("K_ENV", "sk-or-[A-Za-z0-9_-]{20,}", "OpenRouter"),
            advertiseKeySetup = true,
        )
        service.launch(armed, emptyList(), dangerouslySkipPermissions = false, keyPresentNow = false)
        assertTrue(treeContains(tmp.resolve(".claude-cap2"), "sk-or-"), "capture hook must materialize")
    }

    private fun treeContains(dir: java.nio.file.Path, needle: String): Boolean =
        Files.walk(dir).use { paths ->
            paths.filter { Files.isRegularFile(it) }.anyMatch { Files.readString(it).contains(needle) }
        }

    // Declared slots (2026-08-30). The positional heuristic maps four Claude tier slots onto
    // whatever the catalog order happens to be, which on a grok-shaped roster lands TWO models in
    // four slots — grok-4.6 as both OPUS and FABLE, grok-build-latest as both SONNET and HAIKU —
    // so the picker shows the same names repeatedly. A head that declares `slot` per row gets
    // EXACTLY its declared tiers: positional fill for the undeclared ones just re-created the
    // duplication on any roster smaller than four (codex redo verdict — a 2-model head still
    // planted one model in 3 slots), so declaring anything retires positional order outright.
    @Test
    fun `declared slots map each tier to its own model, whatever the catalog order`() {
        val available = listOf("grok-4.6", "grok-build-latest", "grok-4.3", "grok-build-0.1")
        val env = service.launch(
            spec("grok", pinned = "grok-4.6", available = available).copy(
                modelSlots = mapOf(
                    "grok-4.6" to "opus",
                    "grok-4.3" to "sonnet",
                    "grok-build-0.1" to "haiku",
                    "grok-build-latest" to "fable",
                ),
            ),
            extraArgs = emptyList(),
            dangerouslySkipPermissions = false,
        ).env

        assertEquals("grok-4.6", env["ANTHROPIC_DEFAULT_OPUS_MODEL"])
        assertEquals("grok-4.3", env["ANTHROPIC_DEFAULT_SONNET_MODEL"])
        assertEquals("grok-build-0.1", env["ANTHROPIC_DEFAULT_HAIKU_MODEL"])
        assertEquals("grok-build-latest", env["ANTHROPIC_DEFAULT_FABLE_MODEL"])
        val assigned = listOf("OPUS", "SONNET", "HAIKU", "FABLE").map { env["ANTHROPIC_DEFAULT_${it}_MODEL"] }
        assertEquals(assigned.size, assigned.toSet().size, "no model may answer two tiers: $assigned")
    }

    // The exact live target: the grok head serves two models and declares two slots. Positional
    // fill for HAIKU/FABLE handed both back to grok-4.6 (3 tiers, one model) — the very screen
    // this feature exists to clean up. Undeclared tiers must not exist in the recipe at all.
    @Test
    fun `a two-model roster declaring opus and sonnet emits no haiku or fable slot`() {
        val recipe = service.launch(
            spec("grok", pinned = "grok-4.6", available = listOf("grok-4.6", "grok-4.5"))
                .copy(modelSlots = mapOf("grok-4.6" to "opus", "grok-4.5" to "sonnet")),
            extraArgs = emptyList(),
            dangerouslySkipPermissions = false,
        )
        val env = recipe.env

        assertEquals("grok-4.6", env["ANTHROPIC_DEFAULT_OPUS_MODEL"])
        assertEquals("grok-4.5", env["ANTHROPIC_DEFAULT_SONNET_MODEL"])
        // Absent from env is not enough: splice-launch execs `env` WITHOUT -i, so a nested launch
        // inherits the OUTER head's triplets — every un-emitted tier must be actively SCRUBBED or
        // the tier points at a model this head cannot serve (codex redo verdict, 2026-08-30).
        for (tier in listOf("HAIKU", "FABLE")) {
            for (suffix in listOf("MODEL", "MODEL_NAME", "MODEL_DESCRIPTION")) {
                val name = "ANTHROPIC_DEFAULT_${tier}_$suffix"
                assertNull(env[name], "$tier must stay un-set, not duplicated")
                assertTrue(name in recipe.unset, "$name must be scrubbed from the inherited env")
            }
        }
        for (tier in listOf("OPUS", "SONNET")) {
            assertFalse("ANTHROPIC_DEFAULT_${tier}_MODEL" in recipe.unset, "emitted tiers are not scrubbed")
        }
    }

    // DR-44d: the two declaredSlots ignore-claims, previously prose-only (LaunchService.kt:206-208).
    // A stale splice.toml row naming a model the head no longer serves must not plant a tier, and an
    // unrecognized slot string must not crash or leak a bogus tier env — both degrade to
    // "tier not emitted, tier scrubbed", never to a planted lie.
    @Test
    fun `stale-model and unknown-slot declarations are ignored, not planted`() {
        val recipe = service.launch(
            spec("grok", pinned = "grok-4.6", available = listOf("grok-4.6", "grok-4.5"))
                .copy(
                    modelSlots = mapOf(
                        "grok-4.6" to "opus",
                        "grok-retired" to "sonnet",
                        "grok-4.5" to "turbo",
                    ),
                ),
            extraArgs = emptyList(),
            dangerouslySkipPermissions = false,
        )
        val env = recipe.env

        assertEquals("grok-4.6", env["ANTHROPIC_DEFAULT_OPUS_MODEL"])
        assertNull(env["ANTHROPIC_DEFAULT_SONNET_MODEL"], "a stale model row must not plant its tier")
        assertTrue("ANTHROPIC_DEFAULT_SONNET_MODEL" in recipe.unset, "the unplanted tier is still scrubbed")
        assertTrue(env.keys.none { "TURBO" in it }, "an unknown slot string must not leak any env")
        assertTrue(recipe.unset.none { "TURBO" in it }, "and cannot be scrubbed — it is not a tier")
    }

    @Test
    fun `a partial declaration emits only its declared tier - positional order is retired`() {
        val available = listOf("grok-4.6", "grok-build-latest", "grok-4.3")
        val env = service.launch(
            spec("grok", pinned = "grok-4.6", available = available)
                .copy(modelSlots = mapOf("grok-4.3" to "haiku")),
            extraArgs = emptyList(),
            dangerouslySkipPermissions = false,
        ).env

        assertEquals("grok-4.3", env["ANTHROPIC_DEFAULT_HAIKU_MODEL"], "the declared slot wins")
        assertNull(env["ANTHROPIC_DEFAULT_OPUS_MODEL"], "an undeclared tier is omitted, never filled")
        assertNull(env["ANTHROPIC_DEFAULT_SONNET_MODEL"])
        assertNull(env["ANTHROPIC_DEFAULT_FABLE_MODEL"])
    }

    private fun kimiRosterCache() = buildJsonArray {
        addJsonObject {
            put("value", "kimi-k3")
            put("label", "Kimi K3 (256k)")
            put("description", "Kimi K3 (256k)")
            put("context_window", 256_000)
        }
        addJsonObject {
            put("value", "kimi-k2.7-code")
            put("label", "Kimi K2.7 Code")
            put("description", "Kimi K2.7 Code")
            put("context_window", 256_000)
        }
    }

    /** A two-model kimi head, positional: k3 fills opus and fable, k2.7 fills sonnet and haiku. */
    private fun launchKimiWithRepeatedTier(prefix: String): LaunchRecipe = service.launch(
        spec(
            "kimi",
            pinned = "kimi-k3",
            available = listOf("kimi-k3", "kimi-k2.7-code"),
            labels = mapOf("kimi-k3" to "Kimi K3 (256k)", "kimi-k2.7-code" to "Kimi K2.7 Code"),
        ).copy(discoveryPrefix = prefix, modelOptionsCache = kimiRosterCache()),
        extraArgs = emptyList(),
        dangerouslySkipPermissions = false,
    )

    // 2026-09-04: Claude Code 2.1.257 draws one picker row per PLANTED tier and dedupes by value
    // ("opus" vs "fable"), so two tiers on one model listed it twice. A repeated tier is planted
    // under the head's discovery-wrapped id: routed (catalog.contains unwraps), hidden (not on
    // availableModels). The first tier, the labels and the cache rows are untouched.
    @Test
    fun `a tier that repeats an earlier tier's model is planted under the wrapped id`() {
        val env = launchKimiWithRepeatedTier(prefix = "claude-kimi--").env
        assertEquals("kimi-k3", env["ANTHROPIC_DEFAULT_OPUS_MODEL"])
        assertEquals("kimi-k2.7-code", env["ANTHROPIC_DEFAULT_SONNET_MODEL"])
        assertEquals(
            "claude-kimi--kimi-k2.7-code",
            env["ANTHROPIC_DEFAULT_HAIKU_MODEL"],
            "haiku repeats sonnet: wrapped",
        )
        assertEquals("claude-kimi--kimi-k3", env["ANTHROPIC_DEFAULT_FABLE_MODEL"], "fable repeats opus: wrapped")
        assertEquals("Kimi K2.7 Code", env["ANTHROPIC_DEFAULT_HAIKU_MODEL_NAME"], "the label stays the model's own")
        assertEquals("Kimi K3 (256k)", env["ANTHROPIC_DEFAULT_FABLE_MODEL_NAME"])
    }

    @Test
    fun `positional fill plants the repeated frontier and mid tiers under the wrapped id`() {
        val env = service.launch(
            spec("codex", available = listOf("gpt-5.6-sol", "gpt-5.6-terra", "gpt-5.6-luna"))
                .copy(discoveryPrefix = "claude-codex--"),
            extraArgs = emptyList(),
            dangerouslySkipPermissions = false,
        ).env
        assertEquals("gpt-5.6-sol", env["ANTHROPIC_DEFAULT_OPUS_MODEL"])
        assertEquals("gpt-5.6-terra", env["ANTHROPIC_DEFAULT_SONNET_MODEL"])
        assertEquals("gpt-5.6-luna", env["ANTHROPIC_DEFAULT_HAIKU_MODEL"])
        assertEquals(
            "claude-codex--gpt-5.6-sol",
            env["ANTHROPIC_DEFAULT_FABLE_MODEL"],
            "fable shares the frontier: wrapped",
        )
        assertEquals("gpt-5.6-sol", env["ANTHROPIC_DEFAULT_FABLE_MODEL_NAME"], "the label is the model's own")
    }

    @Test
    fun `a head without a discovery prefix keeps the repeated tier bare, and the cache keeps every row`() {
        val recipe = launchKimiWithRepeatedTier(prefix = "")
        assertEquals("kimi-k3", recipe.env["ANTHROPIC_DEFAULT_OPUS_MODEL"])
        val written = Json.parseToJsonElement(
            Files.readString(tmp.resolve(".claude-kimi/.claude.json")),
        ).jsonObject.getValue("additionalModelOptionsCache").jsonArray
        assertEquals(
            listOf("kimi-k3", "kimi-k2.7-code"),
            written.map { it.jsonObject.getValue("value").jsonPrimitive.content },
            "every roster row stays in the cache: Claude Code dedupes them against the tiers itself",
        )
        assertEquals(256_000, written.first().jsonObject.getValue("context_window").jsonPrimitive.long)
    }

    // Discovery retirement (2026-08-30). CLAUDE_CODE_ENABLE_GATEWAY_MODEL_DISCOVERY made the
    // picker query /v1/models, which serves the same roster AGAIN under wrapped provider-prefixed
    // ids — every model listed twice, and Claude Code cannot dedupe the two spellings. Worse, a
    // wrapped ACTIVE id makes it ignore CLAUDE_CODE_MAX_CONTEXT_TOKENS (ab5ca6b: honored for
    // unwrapped names only), which per-head context windows depend on. The materialized bare-id
    // roster is the picker's one source; this arm was red before the env stopped being set.
    @Test
    fun `gateway model discovery stays off - the materialized roster is the picker's one source`() {
        val recipe = service.launch(spec("codex"), extraArgs = emptyList(), dangerouslySkipPermissions = false)
        assertNull(
            recipe.env["CLAUDE_CODE_ENABLE_GATEWAY_MODEL_DISCOVERY"],
            "discovery would re-add every model under a wrapped /v1/models id",
        )
        // Not setting it is not enough — an ambient =1 in the launching shell survives `env`
        // without -i, so the recipe must scrub it (codex redo verdict, 2026-08-30).
        assertTrue(
            "CLAUDE_CODE_ENABLE_GATEWAY_MODEL_DISCOVERY" in recipe.unset,
            "an inherited =1 would re-enable discovery: ${recipe.unset}",
        )
        // The roster still reaches the picker: the materializer wrote the full selected catalog.
        val cfg = tmp.resolve(".claude-codex")
        val settings = Files.readString(cfg.resolve("settings.json"))
        assertTrue(settings.contains("gpt-5.6-sol") && settings.contains("gpt-5.4-mini"))
        assertTrue(Files.readString(cfg.resolve(".claude.json")).contains("additionalModelOptionsCache"))
    }

    @Test
    fun `default recipe is safe - no skip-permissions flag, no warning`() {
        // V4-183: `-c` is no longer a pass-through word (HeadBoundedContinue); a plain flag stands in.
        val recipe = service.launch(spec("codex"), extraArgs = listOf("--verbose"), dangerouslySkipPermissions = false)
        assertFalse(recipe.argv.contains("--dangerously-skip-permissions"))
        assertTrue(recipe.argv.contains("--verbose"))
        assertNull(recipe.warning)
        assertTrue(recipe.env["ANTHROPIC_AUTH_TOKEN"] == "test-inference-token")
    }

    @Test
    fun `opt-in engages the flag and surfaces a warning`() {
        val recipe = service.launch(spec("grok"), extraArgs = emptyList(), dangerouslySkipPermissions = true)
        assertTrue(recipe.argv.contains("--dangerously-skip-permissions"))
        assertNotNull(recipe.warning)
    }

    @Test
    fun `launch preserves ambient no-proxy entries and adds loopback`() {
        val merged = LaunchService(
            ClaudeConfigMaterializer(tmp),
            envReader = { name -> if (name == "NO_PROXY") "corp.internal,localhost" else null },
        ).launch(spec("codex"), emptyList(), dangerouslySkipPermissions = false)
            .env
            .getValue("NO_PROXY")
        assertTrue(merged.contains("corp.internal"))
        assertTrue(merged.contains("127.0.0.1"))
        assertTrue(merged.split(',').count { it == "localhost" } == 1)
    }

    @Test
    fun `codex 5_6 tiers map haiku-luna sonnet-terra opus-and-fable-sol`() {
        // Live catalog order puts mini AFTER the 5.6 tiers; the old heuristic parked haiku on
        // mini and fable on luna. Name-aware slots must pin the 5.6 ladder regardless of order.
        val available = listOf(
            "gpt-5.6-sol",
            "gpt-5.6-terra",
            "gpt-5.6-luna",
            "gpt-5.5",
            "gpt-5.4",
            "gpt-5.4-mini",
            "gpt-5.3-codex-spark",
        )
        val env = service.launch(
            spec("codex", pinned = "gpt-5.6-sol", available = available),
            emptyList(),
            dangerouslySkipPermissions = false,
        ).env
        assertEquals("gpt-5.6-sol", env["ANTHROPIC_DEFAULT_OPUS_MODEL"])
        assertEquals("gpt-5.6-terra", env["ANTHROPIC_DEFAULT_SONNET_MODEL"])
        assertEquals("gpt-5.6-luna", env["ANTHROPIC_DEFAULT_HAIKU_MODEL"])
        assertEquals("gpt-5.6-sol", env["ANTHROPIC_DEFAULT_FABLE_MODEL"])
    }

    @Test
    fun `catalogs without sol-terra-luna keep positional mini fallback`() {
        val available = listOf("grok-4.5", "grok-4.3", "grok-build-latest")
        val env = service.launch(
            spec("grok", pinned = "grok-4.5", available = available),
            emptyList(),
            dangerouslySkipPermissions = false,
        ).env
        assertEquals("grok-4.5", env["ANTHROPIC_DEFAULT_OPUS_MODEL"])
        assertEquals("grok-4.3", env["ANTHROPIC_DEFAULT_SONNET_MODEL"])
        assertEquals("grok-4.3", env["ANTHROPIC_DEFAULT_HAIKU_MODEL"]) // no mini → at(1)
        assertEquals("grok-4.5", env["ANTHROPIC_DEFAULT_FABLE_MODEL"]) // shares frontier
    }

    // ── native-auth heads (campaign claude-head, CH-8) ────────────────────────────────────────
    //
    // Every OTHER head serves a foreign vendor, so the recipe replaces the client's Anthropic
    // session with the gateway bearer and nails /login shut. A claude head's upstream IS Anthropic
    // and it forwards the client's own credential, so all three of those moves are exactly wrong:
    // stripping removes what gets forwarded, planting the bearer overrides it, and disabling /login
    // shuts the only door that can heal a rejected credential.

    private fun nativeSpec() = spec("claude-splice").copy(forwardClientAuth = true)

    @Test
    fun `a native-auth head keeps the client's own credentials`() {
        val recipe = service.launch(nativeSpec(), extraArgs = emptyList(), dangerouslySkipPermissions = false)
        // No CREDENTIAL may be stripped — those variables are what this head forwards. Non-credential
        // hygiene (the discovery scrub) is allowed and wanted: this head's picker reads the same
        // materialized roster as every other.
        for (credential in listOf("ANTHROPIC_API_KEY", "CLAUDE_CODE_OAUTH_TOKEN", "CLAUDE_CODE_OAUTH_REFRESH_TOKEN")) {
            assertFalse(credential in recipe.unset, "a native head must not strip $credential: ${recipe.unset}")
        }
        assertTrue("CLAUDE_CODE_ENABLE_GATEWAY_MODEL_DISCOVERY" in recipe.unset)
        assertNull(recipe.env["ANTHROPIC_AUTH_TOKEN"], "the gateway bearer would override the client's own")
    }

    @Test
    fun `a native-auth head keeps login and logout available`() {
        val env = service.launch(nativeSpec(), extraArgs = emptyList(), dangerouslySkipPermissions = false).env
        assertNull(env["DISABLE_LOGIN_COMMAND"])
        assertNull(env["DISABLE_LOGOUT_COMMAND"])
    }

    @Test
    fun `a native-auth head still gets the proxy and model surface`() {
        val env = service.launch(nativeSpec(), extraArgs = emptyList(), dangerouslySkipPermissions = false).env
        assertEquals("http://127.0.0.1:3099", env["ANTHROPIC_BASE_URL"])
        assertEquals("gpt-5.6-sol", env["ANTHROPIC_MODEL"])
        assertEquals("1", env["SPLICE"])
    }

    // The regression that matters most: foreign heads' ENV must be BYTE-IDENTICAL to before this
    // feature existed. A default-valued flag is easy to leak into the wrong branch. The unset list
    // is allowed exactly one addition since: the discovery scrub (all four tiers emit on a
    // declare-nothing head, so no tier scrubs appear here).
    @Test
    fun `a foreign-vendor head is unchanged - bearer planted, session stripped, login disabled`() {
        val recipe = service.launch(spec("codex"), extraArgs = emptyList(), dangerouslySkipPermissions = false)
        assertEquals("test-inference-token", recipe.env["ANTHROPIC_AUTH_TOKEN"])
        assertEquals("1", recipe.env["DISABLE_LOGIN_COMMAND"])
        assertEquals("1", recipe.env["DISABLE_LOGOUT_COMMAND"])
        assertEquals(
            listOf(
                "ANTHROPIC_API_KEY",
                "CLAUDE_CODE_OAUTH_TOKEN",
                "CLAUDE_CODE_OAUTH_REFRESH_TOKEN",
                "CLAUDE_CODE_ENABLE_GATEWAY_MODEL_DISCOVERY",
            ),
            recipe.unset,
        )
    }

    // V4-115: `-r` with NO id is Claude Code's session PICKER, and the picker is head-bounded. A bare
    // `-r`, `-c`, and a plain launch must therefore adopt NOTHING — if any of them reached the
    // cross-head resolver, the foreign session would appear in this head's tree and stop being
    // private to its own.
    @Test
    fun `a bare -r is the picker and never adopts a sibling head's session`() {
        val sibling = seedSiblingSession("abc-123")
        val mine = spec("picker").copy(trees = HeadTrees(tmp.resolve(".claude-picker"), listOf(sibling)))

        listOf(emptyList(), listOf("-r")).forEach { args ->
            val recipe = service.launch(mine, extraArgs = args, dangerouslySkipPermissions = false)
            assertNull(recipe.warning, "a picker launch has nothing to report: ${recipe.warning}")
        }
        // V4-183: a bare -c with no session of this head in the cwd is a NEW session, said once —
        // never the sibling's, and never the client's own newest-in-tree continue.
        val cwd = Files.createDirectories(tmp.resolve("home-x")).toString()
        val continued = service.launch(mine, extraArgs = listOf("-c"), dangerouslySkipPermissions = false, cwd = cwd)
        assertFalse(continued.argv.contains("-c"), "the client's -c never reaches the client: ${continued.argv}")
        assertTrue(continued.warning.orEmpty().startsWith("no session of this head in $cwd"), continued.warning)
        assertFalse(
            Files.exists(tmp.resolve(".claude-picker/projects/-home-x/abc-123.jsonl")),
            "the picker must see this head's tree only",
        )
    }

    // V4-183: the whole path — a session this head started (recorded by its SessionStart hook)
    // is what a later bare -c in the same cwd resumes, by name, through the ordinary resume flag.
    @Test
    fun `a bare -c resumes this head's own newest session in the cwd, by name`() {
        val mine = spec("owner")
        val cwd = Files.createDirectories(tmp.resolve("owner-work")).toString()
        val transcript = tmp.resolve(".claude-owner/projects/-owner-work/own-session.jsonl")
        Files.createDirectories(transcript.parent)
        Files.writeString(transcript, "")
        SessionOwnership(mine.trees.own).record("own-session", cwd, transcript)

        val recipe = service.launch(mine, extraArgs = listOf("-c"), dangerouslySkipPermissions = false, cwd = cwd)

        val argv = recipe.argv
        assertEquals(listOf("--resume", "own-session"), argv.takeLast(2), argv.toString())
        assertFalse(argv.contains("-c"))
        assertNull(recipe.warning, "resuming one's own session is the quiet path: ${recipe.warning}")
    }

    @Test
    fun `-r SESSION_ID adopts across heads, announces it, and leaves argv untouched`() {
        val sibling = seedSiblingSession("abc-123")
        val mine = spec("adopter").copy(trees = HeadTrees(tmp.resolve(".claude-adopter"), listOf(sibling)))

        val recipe = service.launch(mine, extraArgs = listOf("-r", "abc-123"), dangerouslySkipPermissions = false)

        val adopted = tmp.resolve(".claude-adopter/projects/-home-x/abc-123.jsonl")
        assertTrue(Files.isRegularFile(adopted), "the sibling's transcript must land in THIS head's tree")
        assertTrue(
            recipe.warning.orEmpty().contains("copied into this head"),
            "an adoption is an explicit act and is said out loud: ${recipe.warning}",
        )
        assertEquals(
            listOf("claude", "-r", "abc-123"),
            recipe.argv,
            "the copy is additive — the client is still launched with the caller's own argv",
        )
    }

    /** A SIBLING head's projects tree holding [sessionId] under the encoded cwd `-home-x`. The head
     *  name is fixed and distinct from every calling head in these tests: seeding the CALLING head's
     *  own dir would make the adoption a no-op (HeadOwned) and pin nothing. */
    private fun seedSiblingSession(sessionId: String): Path {
        val path = tmp.resolve(".claude-sibling/projects/-home-x/$sessionId.jsonl")
        Files.createDirectories(path.parent)
        Files.writeString(
            path,
            """{"type":"user","sessionId":"$sessionId","message":{"role":"user","content":"hi"}}
{"type":"assistant","sessionId":"$sessionId","message":{"id":"m1","model":"k3-256k","content":[]}}
""",
        )
        return tmp.resolve(".claude-sibling")
    }
}
