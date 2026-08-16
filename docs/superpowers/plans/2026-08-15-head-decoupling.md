# Head Decoupling & Boot Robustness Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close every recorded boundary of the claude-head campaign — profile coupling, silent config drift, header fidelity, misattributed health — so each head is a fully declared, independently configurable unit and a wrong config is impossible to run silently (concept #924: make drift not compile).

**Architecture:** Three moves. (1) A named `PassthroughProfile` (quirks base + base headers + device-identity flag) replaces the auth-kind-implied bundles in `Daemon.passthroughProvider`, declarable per provider in TOML. (2) A pure `TopologyValidator` in `:core` runs at boot and in doctor; findings refuse boot (fail-closed) and feed a `ConfigRepair` CLI flow that detects installed coding harnesses (claude / kimi / codex / grok) and offers an interactive fix session with a prepared prompt. (3) The remaining deferred items (header multi-value fidelity, caller-fault health, ambient-credential warning, invariant docs) land as small independent tasks, each with a konsist/arch law where a structural fence exists.

**Tech Stack:** Kotlin/Gradle multi-module (`gateway/`), ktoml + kotlinx.serialization, Ktor, JUnit 5, konsist (`:arch-tests`), campaign ledger via `dev/campaigns/manifest.py`.

## Global Constraints

- **KOTLIN STYLE LAW (operator, 2026-08-15 — supersedes any snippet below that violates it):** no top-level functions, no `companion object`, anywhere in main sources. Top-level `const val` is allowed; test paths are exempt. Global PreToolUse hooks 34/36 enforce this WHOLE-FILE: an edit to a main-source `.kt` only writes if the file ends fully clean. Consequently **every task migrates each main-source file it touches in the same edit** — behavior-preserving, goldens frozen, detekt budgets met by decomposing into injected/helper classes (never suppression), konsist `NEW:`/`PORT-OF` header on new files. Where a snippet below shows a `companion object` or top-level `fun`, implement the same contract without one (e.g. `PassthroughProfile.fromWire(x)` becomes `PassthroughProfile.entries.firstOrNull { it.wire == x }` at call sites; file-level helpers become private methods or small injected classes). Task 10 sweeps every file the earlier tasks didn't touch.

- **KIMI BYTE-IDENTITY:** kimi's built requests and translator behavior stay byte-identical. `PassthroughGoldenTest` goldens under `gateway/dialect-anthropic-passthrough/src/test/resources/goldens/` are FROZEN — if their bytes move, the change is wrong.
- **NEVER-BELOW-STATUS-QUO:** a live `splice.toml` that boots and works today must keep booting with identical wire behavior. Profile defaults derive from auth kind exactly as the current arms do: `kimi-oauth` → KIMI, `api-key` → KIMI, `client` → NEUTRAL. Validation only rejects configs that are *already wrong* (typo'd kind, inert key, malformed header name) — never a working one.
- **FAIL-CLOSED BOOT + REPAIR OFFER (operator decision, 2026-08-15):** a validation finding refuses daemon boot listing every finding at once; the CLI then reports the issues, detects installed harnesses, and offers an interactive fix session. Never boot with a finding; never fix silently.
- **LEDGER CLI ONLY:** all campaign state via `python3 dev/campaigns/manifest.py dev/campaigns/head-decoupling.toml <verb>` — raw ledger edits are hook-blocked.
- **BRANCH:** all work lands on `feat/claude-head` (operator instruction). No push except by the orchestrator after a green gate.
- **VERIFY LADDER:** builders run the named module tests only; `npm run gate` (full ladder) is the orchestrator's verify act, hash printed beside the result.
- **ARCH LAWS:** every new production file header declares `NEW:` (konsist law in `:arch-tests`); dialect modules never import `splice.core.topology` (assembly-point idiom, `Daemon.kt:238`); no `@Suppress` for detekt findings — fix structurally.

---

## File Structure

| File | Responsibility |
|---|---|
| `gateway/dialect-anthropic-passthrough/src/main/kotlin/splice/dialect/passthrough/PassthroughProfile.kt` | **Create.** The named profile registry: quirks base, base headers, device-identity flag. Single home for kimi's vendor bundle (joins the existing `PassthroughQuirks.kimi()` precedent). |
| `gateway/core/src/main/kotlin/splice/core/topology/TopologyValidator.kt` | **Create.** Pure validation: findings model + all rules. No I/O, fully unit-testable. |
| `gateway/app/src/main/kotlin/splice/app/cli/ConfigRepair.kt` | **Create.** Harness detection + interactive repair offer + prompt builder. |
| `gateway/core/src/main/kotlin/splice/core/topology/Topology.kt` | Modify: `ProviderConfig` gains `profile: String?`. |
| `gateway/app/src/main/kotlin/splice/app/Daemon.kt` | Modify: dispatch consumes resolved profile; boot validation call; boot transparency log. `KIMI_BASE_HEADERS` moves out (to PassthroughProfile). |
| `gateway/app/src/main/kotlin/splice/app/Main.kt` | Modify: boot path surfaces validation findings through ConfigRepair. |
| `gateway/app/src/main/kotlin/splice/app/cli/DoctorCommand.kt` | Modify: doctor runs the validator and prints findings + repair offer. |
| `gateway/gateway/src/main/kotlin/splice/gateway/head/HeadServer.kt` | Modify: multi-value capture for `anthropic-beta`; forwarded-header-names telemetry. |
| `gateway/gateway/src/main/kotlin/splice/gateway/head/HeadHealthCounters.kt` | Modify: `caller()` bucket. |
| `gateway/gateway/src/main/kotlin/splice/gateway/head/TurnDriver.kt` | Modify: route caller-fault 401s to the caller bucket. |
| `gateway/control/src/main/kotlin/splice/control/ControlServer.kt` | Modify: expose the caller bucket in `/api/heads`. |
| `gateway/control/src/main/kotlin/splice/control/LaunchService.kt` | Modify: ambient-credential warning on native-auth heads. |
| `gateway/dialect-anthropic-passthrough/src/main/kotlin/splice/dialect/passthrough/PassthroughRequestBuilder.kt` | Modify: invariant comment names the four unconditional transforms (docs only). |
| `gateway/arch-tests/src/test/kotlin/ArchitectureLawsTest.kt` | Modify: two new konsist laws. |
| `config/splice.example.toml` | Modify: kimi declares `profile = "kimi"`; prose documents profile semantics. |
| `dev/campaigns/head-decoupling.toml` | **Create** (Task 0, via manifest.py only). |

Test files: one per production file touched, named in each task.

---

### Task 0: Campaign ledger

**Files:**
- Create: `dev/campaigns/head-decoupling.toml` (via `manifest.py add` ONLY — raw writes are hook-blocked)

**Interfaces:**
- Produces: ledger items HD-1..HD-9 matching Tasks 1–9; every later task's commit message references its item id.

- [ ] **Step 1: Create the ledger with its law header**

The manifest CLI creates a ledger on first `add`. Add the first item, then `add-law` the campaign laws:

```bash
cd /home/marcos/Documents/dev/projects/mythos/repo
L=dev/campaigns/head-decoupling.toml
M="python3 dev/campaigns/manifest.py $L"
$M add --id HD-1 --phase profile --title "PassthroughProfile registry (dialect module): named data bundle {quirks base, base headers, usesDeviceIdentity} for NEUTRAL and KIMI; ProviderConfig gains nullable profile key; Daemon resolves explicit > auth-kind default (kimi-oauth->KIMI, api-key->KIMI, client->NEUTRAL) and all three dispatch arms consume the resolved profile; KIMI_BASE_HEADERS moves into the profile; example TOML documents it on kimi's entry. Goldens green UNMODIFIED." --files "gateway/dialect-anthropic-passthrough/src/main/kotlin/splice/dialect/passthrough/PassthroughProfile.kt, gateway/dialect-anthropic-passthrough/src/test/kotlin/PassthroughProfileTest.kt, gateway/core/src/main/kotlin/splice/core/topology/Topology.kt, gateway/core/src/test/kotlin/TopologyConfigOverridesTest.kt, gateway/app/src/main/kotlin/splice/app/Daemon.kt, gateway/app/src/test/kotlin/splice/app/PassthroughQuirksOverlayTest.kt, gateway/app/src/test/kotlin/ExampleConfigTest.kt, config/splice.example.toml" --verify "cd gateway && ./gradlew :dialect-anthropic-passthrough:test :core:test :app:test --console=plain"
$M add-law "PROVENANCE — plan docs/superpowers/plans/2026-08-15-head-decoupling.md; boundaries from claude-head CH-12 deferred items (a)-(i). Concept #924: violations become impossible to express, not review comments."
$M add-law "NEVER-BELOW-STATUS-QUO — kimi goldens frozen; profile defaults reproduce today's arm behavior exactly; validation never rejects a working config."
$M add-law "FAIL-CLOSED BOOT + REPAIR OFFER (operator, 2026-08-15) — findings refuse boot listing ALL at once; CLI detects installed harnesses (claude/kimi/codex/grok) and offers an interactive fix session. Never boot on a finding, never fix silently."
$M add-law "BRANCH feat/claude-head; builders module-tests only, no push; gate + commit chain are the orchestrator's."
```

- [ ] **Step 2: Add items HD-2..HD-9** — one `add` per remaining task below, `--title` copied from the task's summary line, `--files` from its Files block, `--verify` from its final test step.

- [ ] **Step 3: Verify** — `$M list` shows 9 items, all `todo`. `$M selftest` green.

- [ ] **Step 4: Commit**

```bash
git add dev/campaigns/head-decoupling.toml docs/superpowers/plans/2026-08-15-head-decoupling.md
git commit -m "chore(campaigns): open the head-decoupling campaign (plan + ledger)"
```

---

### Task 1: PassthroughProfile registry (HD-1)

Closes deferred item (a)'s root: the {quirks, headers, identity} bundle is currently implied by auth kind across three dispatch arms in `Daemon.kt:554-587`; a new vendor cannot escape Kimi's bundle and a reader cannot see it.

**Files:**
- Create: `gateway/dialect-anthropic-passthrough/src/main/kotlin/splice/dialect/passthrough/PassthroughProfile.kt`
- Create: `gateway/dialect-anthropic-passthrough/src/test/kotlin/PassthroughProfileTest.kt`
- Modify: `gateway/core/src/main/kotlin/splice/core/topology/Topology.kt:73-98` (ProviderConfig)
- Modify: `gateway/app/src/main/kotlin/splice/app/Daemon.kt:245` (KIMI_BASE_HEADERS) and `:554-587` (dispatch)
- Modify: `config/splice.example.toml` (kimi entry + prose)
- Test: `gateway/core/src/test/kotlin/TopologyConfigOverridesTest.kt`, `gateway/app/src/test/kotlin/ExampleConfigTest.kt`, `gateway/app/src/test/kotlin/splice/app/PassthroughQuirksOverlayTest.kt`

**Interfaces:**
- Consumes: `PassthroughQuirks.kimi(tag: String)` (existing, `PassthroughRequestBuilder.kt`), `ProviderConfig.staticHeaders` (existing).
- Produces: `enum class PassthroughProfile { NEUTRAL, KIMI }` with members `fun quirksBase(providerTag: String): PassthroughQuirks`, `val baseHeaders: Map<String, String>`, `val usesDeviceIdentity: Boolean`, and `companion fun fromWire(wire: String): PassthroughProfile?` (`"neutral"`/`"kimi"`, else null). `ProviderConfig.profile: String?` (`@SerialName("profile")`). Task 2 consumes `fromWire` for validation; Task 4 logs the resolved profile name.

- [ ] **Step 1: Write the failing profile test**

```kotlin
// gateway/dialect-anthropic-passthrough/src/test/kotlin/PassthroughProfileTest.kt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.dialect.passthrough.PassthroughProfile
import splice.dialect.passthrough.PassthroughQuirks

class PassthroughProfileTest {

    @Test
    fun `KIMI profile is byte-for-byte the bundle the daemon arms hardcode today`() {
        val p = PassthroughProfile.KIMI
        assertEquals(PassthroughQuirks.kimi("kimi"), p.quirksBase("kimi"))
        assertEquals("2023-06-01", p.baseHeaders["anthropic-version"])
        assertTrue(p.baseHeaders.getValue("User-Agent").startsWith("KimiCLI/"))
        assertTrue(p.usesDeviceIdentity)
    }

    @Test
    fun `NEUTRAL profile carries nothing vendor-shaped`() {
        val p = PassthroughProfile.NEUTRAL
        assertEquals(PassthroughQuirks(providerTag = "x"), p.quirksBase("x"))
        assertTrue(p.baseHeaders.isEmpty())
        assertFalse(p.usesDeviceIdentity)
    }

    @Test
    fun `fromWire is closed - unknown strings are null, never a fallback`() {
        assertEquals(PassthroughProfile.KIMI, PassthroughProfile.fromWire("kimi"))
        assertEquals(PassthroughProfile.NEUTRAL, PassthroughProfile.fromWire("neutral"))
        assertNull(PassthroughProfile.fromWire("kimo"))
    }
}
```

- [ ] **Step 2: Run to verify it fails** — `cd gateway && ./gradlew :dialect-anthropic-passthrough:test --tests PassthroughProfileTest --console=plain`. Expected: compile error, `PassthroughProfile` unresolved.

- [ ] **Step 3: Implement PassthroughProfile**

```kotlin
// gateway/dialect-anthropic-passthrough/src/main/kotlin/splice/dialect/passthrough/PassthroughProfile.kt
// NEW: the named vendor bundle a passthrough provider bases on — quirks, static base headers, and
// whether a computed device identity rides along. ONE home (joining PassthroughQuirks.kimi() as the
// single definition of kimi's shape) so the daemon's dispatch arms carry no vendor data of their
// own and a TOML `profile` key can select or escape a bundle explicitly (#924: the bundle is a
// closed enum — a typo'd profile cannot resolve to anything).
package splice.dialect.passthrough

public enum class PassthroughProfile(
    public val wire: String,
    public val baseHeaders: Map<String, String>,
    public val usesDeviceIdentity: Boolean,
) {
    /** Faithful passthrough: no deformations, no vendor headers, no identity. */
    NEUTRAL("neutral", emptyMap(), usesDeviceIdentity = false),

    /** Kimi's /coding surface: full deformation set + vendor headers + X-Msh-* device identity. */
    KIMI(
        "kimi",
        mapOf("anthropic-version" to "2023-06-01", "User-Agent" to "KimiCLI/1.5"),
        usesDeviceIdentity = true,
    ),
    ;

    public fun quirksBase(providerTag: String): PassthroughQuirks = when (this) {
        NEUTRAL -> PassthroughQuirks(providerTag = providerTag)
        KIMI -> PassthroughQuirks.kimi(providerTag)
    }

    public companion object {
        /** Closed lookup: unknown wire = null. Callers REFUSE, never fall back (boot validation). */
        public fun fromWire(wire: String): PassthroughProfile? = entries.firstOrNull { it.wire == wire }
    }
}
```

**IMPORTANT:** copy the `User-Agent` value from the current `KIMI_BASE_HEADERS` at `Daemon.kt:245` verbatim — do not trust the `KimiCLI/1.5` literal above; the daemon constant is the source of truth being moved.

- [ ] **Step 4: Run the profile test** — same command. Expected: PASS (adjust the UA assertion to the moved literal if it differs).

- [ ] **Step 5: Add `profile` to ProviderConfig** — in `Topology.kt` inside `ProviderConfig`, after `val auth: AuthConfig`:

```kotlin
    /** anthropic-passthrough only: the named vendor bundle this provider bases on ("kimi" |
     *  "neutral"). ABSENT = derived from auth kind exactly as before the key existed (kimi-oauth
     *  and api-key -> kimi, client -> neutral), so a pre-existing splice.toml keeps its behavior.
     *  Kept a String at the TOML boundary (the AuthKind idiom); TopologyValidator refuses unknown
     *  values at boot — never a silent fallback. */
    val profile: String? = null,
```

Add to `TopologyConfigOverridesTest.kt` (mirror the file's existing parse-assert idiom):

```kotlin
    @Test
    fun `profile key parses and defaults to null`() {
        val withProfile = parseProvider("""profile = "neutral"""")   // use the file's existing helper for a provider block
        assertEquals("neutral", withProfile.profile)
        assertNull(parseProvider("").profile)
    }
```

(The file has an existing helper that parses a minimal provider TOML block — read it first and reuse it; if it is named differently, keep the assertions and swap the helper name.)

- [ ] **Step 6: Run core tests** — `./gradlew :core:test --console=plain`. Expected: PASS.

- [ ] **Step 7: Rewire the Daemon dispatch.** In `Daemon.kt`: delete `KIMI_BASE_HEADERS` (line 245); add resolution next to `passthroughProvider`:

```kotlin
    // Explicit profile wins; absent derives the pre-profile behavior per auth kind. Unknown
    // strings never reach here — TopologyValidator refused boot (HD-2). The !! documents that.
    private fun resolvedProfile(cfg: ProviderConfig): PassthroughProfile =
        cfg.profile?.let { PassthroughProfile.fromWire(it)!! }
            ?: if (cfg.auth.kind == CLIENT) PassthroughProfile.NEUTRAL else PassthroughProfile.KIMI
```

Then in `passthroughProvider` replace the per-arm bundles: the client arm uses `profile.quirksBase(key)` instead of `PassthroughQuirks(providerTag = key)`; the kimi-oauth/api-key arm uses `base = profile.quirksBase(key)`, `baseHeaders = profile.baseHeaders`, and constructs `KimiDeviceIdentity` ONLY when `profile.usesDeviceIdentity` (pass `identityHeaders = identity::headers` when true, omit when false — check `passthroughProviderFor`'s parameter default). Keep the arms' auth construction untouched.

- [ ] **Step 8: Pin the new escape hatch in an app-level test.** In `PassthroughQuirksOverlayTest.kt` (it already assembles providers from TOML — mirror its harness):

```kotlin
    @Test
    fun `an api-key provider declaring profile neutral gets no kimi bundle`() {
        // parse a provider block with: dialect anthropic-passthrough, auth api-key, profile = "neutral"
        // assemble via the file's existing daemon-assembly helper, then assert on the built provider:
        //   - extraHeaders carry NO User-Agent and NO anthropic-version (nothing beyond TOML extra_headers)
        //   - the upstream request records NO x-msh-* header (no device identity manufactured)
        // Reuse the file's existing header-recording upstream; assert sent.keys.none { it.startsWith("x-msh-") }
    }
```

Write the real assertions against that file's existing helpers (read it first — it landed in CH-3 and already proves kimi's shape from declared data). Also KEEP its existing kimi tests green unchanged: that is the status-quo proof.

- [ ] **Step 9: Document in the example TOML.** In `config/splice.example.toml`, kimi's provider entry gains `profile = "kimi"` with one comment line (`# The named vendor bundle; absent would derive the same from auth kind. Declared as documentation.`), and the claude-splice prose gains one line noting a client head bases on `neutral`. Extend `ExampleConfigTest.kt`: assert kimi's parsed `profile == "kimi"` and anthropic's `profile == null`.

- [ ] **Step 10: Run the full task verify** — `./gradlew :dialect-anthropic-passthrough:test :core:test :app:test --console=plain`. Expected: PASS, and `git status` shows zero changes under `resources/goldens/`.

- [ ] **Step 11: Ledger + commit**

```bash
$M note HD-1 "LANDED: <one-line summary + evidence counts>" && $M set-status HD-1 done
git add -A ':!dev/campaigns' && git add dev/campaigns/head-decoupling.toml
git commit -m "feat(dialect): name the passthrough vendor bundles as PassthroughProfile (HD-1)"
```

---

### Task 2: TopologyValidator — fail-closed boot (HD-2)

Closes: typo'd `auth.kind` silently running with Kimi's profile; passthrough quirks / `extra_headers` inert on openai dialects (deferred g); malformed header names (deferred h); unknown `profile`.

**Files:**
- Create: `gateway/core/src/main/kotlin/splice/core/topology/TopologyValidator.kt`
- Create: `gateway/core/src/test/kotlin/TopologyValidatorTest.kt`
- Modify: `gateway/app/src/main/kotlin/splice/app/Main.kt:54` (boot path), `gateway/app/src/main/kotlin/splice/app/Daemon.kt` (init guard)

**Interfaces:**
- Consumes: `Topology` (core), `AuthKind.from(wire)`, `PassthroughProfile.fromWire` — NO: core cannot import the dialect module. The validator owns its own closed set of profile wires: `setOf("neutral", "kimi")`. A konsist law in Task 8 pins the two lists equal is NOT possible across modules cheaply — instead `TopologyValidatorTest` and `PassthroughProfileTest` each pin the literal set, and Task 8's arch law verifies `fromWire` callers; drift between the two literals fails Task 2's test the moment a profile is added without updating both (add a comment in each file pointing at the other).
- Produces: `data class ConfigFinding(val providerKey: String, val key: String, val problem: String, val fix: String)`; `object TopologyValidator { fun validate(topology: Topology): List<ConfigFinding> }`. Task 3 consumes `ConfigFinding` for the repair prompt; `Daemon` throws `TopologyValidationException(findings)` when non-empty.

- [ ] **Step 1: Write the failing validator tests**

```kotlin
// gateway/core/src/test/kotlin/TopologyValidatorTest.kt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.topology.TopologyValidator

// Build Topology values with the same parse helper TopologyConfigOverridesTest uses (read that
// file first; reuse its minimal-TOML builder so these tests exercise the real parse path).
class TopologyValidatorTest {

    @Test
    fun `a clean kimi + client + api-key topology has zero findings`() {
        assertEquals(emptyList<Any>(), TopologyValidator.validate(parseFixture(CLEAN_TOPOLOGY)))
    }

    @Test
    fun `a typo'd auth kind is a finding naming the valid kinds`() {
        val f = TopologyValidator.validate(parseFixture(topologyWith("""auth = { kind = "api-kee" }""")))
        assertEquals(1, f.size)
        assertTrue(f[0].problem.contains("api-kee"))
        assertTrue(f[0].fix.contains("api-key"))
    }

    @Test
    fun `an unknown profile is a finding, never a fallback`() {
        val f = TopologyValidator.validate(parseFixture(topologyWith("""profile = "kimo"""")))
        assertTrue(f.single().fix.contains("neutral") && f.single().fix.contains("kimi"))
    }

    @Test
    fun `a passthrough-only quirk on an openai dialect is a finding naming the key`() {
        val f = TopologyValidator.validate(parseFixture(openaiProviderWith("""quirks = { mfjs = true }""")))
        assertTrue(f.single().key == "quirks.mfjs")
    }

    @Test
    fun `extra_headers on a dialect that never sends them is a finding`() {
        val f = TopologyValidator.validate(parseFixture(openaiProviderWith("""extra_headers = { x = "y" }""")))
        assertTrue(f.single().key == "extra_headers")
    }

    @Test
    fun `a header name outside the RFC token charset is a finding`() {
        val f = TopologyValidator.validate(
            parseFixture(topologyWith("""extra_headers = { "anthropic version" = "x" }""")),
        )
        assertTrue(f.single().problem.contains("anthropic version"))
    }

    @Test
    fun `findings are exhaustive - a config with three problems reports all three at once`() {
        // one topology carrying a typo'd kind + an inert quirk + a bad header name -> 3 findings
    }
}
```

Fill `parseFixture` / `topologyWith` / `openaiProviderWith` from `TopologyConfigOverridesTest`'s existing minimal-TOML helpers (a passthrough provider block for `topologyWith`, an `openai-responses` block for `openaiProviderWith`).

- [ ] **Step 2: Run to verify failure** — `./gradlew :core:test --tests TopologyValidatorTest --console=plain`. Expected: compile error.

- [ ] **Step 3: Implement the validator**

```kotlin
// gateway/core/src/main/kotlin/splice/core/topology/TopologyValidator.kt
// NEW: fail-closed boot validation (#924). Parse stays permissive (AuthKind.kt's documented
// decision: an unknown kind never fails CONFIG PARSE); ASSEMBLY is where a wrong config becomes a
// wrong runtime, so this is where it becomes impossible instead. Pure — no I/O — so every rule is
// a unit test. Findings are exhaustive: the operator sees every problem in one boot attempt.
package splice.core.topology

public data class ConfigFinding(
    val providerKey: String,
    val key: String,
    val problem: String,
    val fix: String,
)

public object TopologyValidator {

    // Keep in lockstep with PassthroughProfile.fromWire (dialect module — core cannot import it).
    // Both files' tests pin this literal; adding a profile updates both or a test fails.
    private val PROFILE_WIRES = setOf("neutral", "kimi")

    private val PASSTHROUGH_KINDS = setOf("kimi-oauth", "api-key", "client")
    private val OPENAI_KINDS = setOf("chatgpt-oauth", "grok-oauth", "api-key")

    /** QuirksConfig keys consumed ONLY by the passthrough dialect (Topology.kt tags them). */
    private val PASSTHROUGH_ONLY_QUIRKS: Map<String, (QuirksConfig) -> Any?> = mapOf(
        "mfjs" to { q -> q.mfjs },
        "block_allowlist" to { q -> q.blockAllowlist },
        "strip_cache_control" to { q -> q.stripCacheControl },
        "synthesize_signatures" to { q -> q.synthesizeSignatures },
        "map_thinking_adaptive" to { q -> q.mapThinkingAdaptive },
        "strip_sampling_params" to { q -> q.stripSamplingParams },
    )

    /** Keys whose Topology.kt comment says "openai-responses only" / "openai-chat only". */
    private val OPENAI_ONLY_QUIRKS: Map<String, (QuirksConfig) -> Any?> = mapOf(
        "reasoning_cache" to { q -> q.reasoningCache },
        "parallel_tool_calls" to { q -> q.parallelToolCalls },
        "websocket" to { q -> q.webSocket },
        "tool_surface" to { q -> q.toolSurface },
        "reasoning_effort" to { q -> q.reasoningEffort },
    )

    private val HEADER_TOKEN = Regex("^[!#$%&'*+.^_`|~0-9A-Za-z-]+$") // RFC 9110 field-name

    public fun validate(topology: Topology): List<ConfigFinding> = buildList {
        topology.providers.forEach { (key, cfg) ->
            addAll(validateAuthKind(key, cfg))
            addAll(validateProfile(key, cfg))
            addAll(validateQuirkScope(key, cfg))
            addAll(validateExtraHeaders(key, cfg))
        }
    }

    private fun validateAuthKind(key: String, cfg: ProviderConfig): List<ConfigFinding> {
        val accepted = when (cfg.dialect) {
            Dialect.ANTHROPIC_PASSTHROUGH -> PASSTHROUGH_KINDS
            Dialect.OPENAI_RESPONSES, Dialect.OPENAI_CHAT -> OPENAI_KINDS
        }
        return if (cfg.auth.kind in accepted) emptyList() else listOf(
            ConfigFinding(
                key, "auth.kind",
                "\"${cfg.auth.kind}\" is not a kind the ${cfg.dialect} dialect dispatches",
                "one of: ${accepted.sorted().joinToString(", ")}",
            ),
        )
    }

    private fun validateProfile(key: String, cfg: ProviderConfig): List<ConfigFinding> {
        val p = cfg.profile ?: return emptyList()
        if (cfg.dialect != Dialect.ANTHROPIC_PASSTHROUGH) {
            return listOf(ConfigFinding(key, "profile", "profile only applies to anthropic-passthrough", "remove it"))
        }
        return if (p in PROFILE_WIRES) emptyList() else listOf(
            ConfigFinding(key, "profile", "unknown profile \"$p\"", "one of: ${PROFILE_WIRES.sorted().joinToString(", ")}"),
        )
    }

    private fun validateQuirkScope(key: String, cfg: ProviderConfig): List<ConfigFinding> {
        val wrong = when (cfg.dialect) {
            Dialect.ANTHROPIC_PASSTHROUGH -> OPENAI_ONLY_QUIRKS
            else -> PASSTHROUGH_ONLY_QUIRKS
        }
        return wrong.mapNotNull { (name, read) ->
            read(cfg.quirks)?.let {
                ConfigFinding(key, "quirks.$name", "$name is ignored by the ${cfg.dialect} dialect", "remove it")
            }
        }
    }

    private fun validateExtraHeaders(key: String, cfg: ProviderConfig): List<ConfigFinding> = buildList {
        if (cfg.extraHeaders.isNotEmpty() && cfg.dialect != Dialect.ANTHROPIC_PASSTHROUGH) {
            add(ConfigFinding(key, "extra_headers", "extra_headers reach the wire only on anthropic-passthrough", "remove it"))
        }
        cfg.staticHeaders.keys.filterNot { HEADER_TOKEN.matches(it) }.forEach {
            add(ConfigFinding(key, "extra_headers", "\"$it\" is not a legal HTTP header name", "RFC 9110 token characters only"))
        }
    }
}
```

**Adjust to reality while implementing:** the exact `Topology` root shape (`topology.providers`) and `Dialect` enum arms must be read from `Topology.kt`, and if any openai provider in the repo's own `config/splice.example.toml` legitimately sets an `OPENAI_ONLY_QUIRKS` key on the *other* openai dialect, split that map per-dialect (responses vs chat) before flagging — `reasoning_effort` is chat-tagged, the other four responses-tagged. NEVER-BELOW-STATUS-QUO: `./gradlew :app:test --tests ExampleConfigTest` green proves the shipped example validates clean — add that assertion:

```kotlin
    @Test
    fun `the shipped example config validates clean`() {
        assertEquals(emptyList<Any>(), TopologyValidator.validate(parsedExampleTopology))
    }
```

(in `ExampleConfigTest.kt`, reusing its parsed-example value.)

- [ ] **Step 4: Run validator tests** — `./gradlew :core:test --tests TopologyValidatorTest --console=plain`. Expected: PASS.

- [ ] **Step 5: Wire the boot guard.** In `Daemon` init (before any head assembly):

```kotlin
        val findings = TopologyValidator.validate(topology)
        require(findings.isEmpty()) {
            "splice.toml refused (fix every line, then restart):\n" + findings.joinToString("\n") {
                "  [providers.${it.providerKey}] ${it.key}: ${it.problem} — fix: ${it.fix}"
            }
        }
```

Daemon-level test (in the app module, mirroring an existing Daemon-construction test): constructing a Daemon from a topology with a typo'd kind throws with the finding text; a clean topology constructs. **Check existing app tests still construct clean** — any test fixture with an invalid kind must be fixed, not the validator loosened.

- [ ] **Step 6: Run the module tests** — `./gradlew :core:test :app:test --console=plain`. Expected: PASS.

- [ ] **Step 7: Ledger + commit** — `$M note HD-2 ... && $M set-status HD-2 done`; commit `feat(core): fail-closed topology validation at boot (HD-2)`.

---

### Task 3: ConfigRepair — harness detection + interactive fix offer (HD-3)

The operator-decided UX: a refused boot (and doctor) reports findings, detects which coding harnesses are installed, and offers to start an interactive session with a prepared repair prompt.

**Files:**
- Create: `gateway/app/src/main/kotlin/splice/app/cli/ConfigRepair.kt`
- Create: `gateway/app/src/test/kotlin/splice/app/cli/ConfigRepairTest.kt`
- Modify: `gateway/app/src/main/kotlin/splice/app/Main.kt:54` region (catch the validation failure), `gateway/app/src/main/kotlin/splice/app/cli/DoctorCommand.kt` (run validator, print findings + offer)

**Interfaces:**
- Consumes: `ConfigFinding` (Task 2).
- Produces: `class ConfigRepair(private val binaryExists: (String) -> Boolean, private val isTty: () -> Boolean, private val out: (String) -> Unit, private val readLine: () -> String?, private val exec: (List<String>) -> Int)` with `fun detectHarnesses(): List<Harness>`, `fun repairPrompt(configPath: Path, findings: List<ConfigFinding>): String`, `fun offer(configPath: Path, findings: List<ConfigFinding>)`. `data class Harness(val name: String, val binary: String)`.

- [ ] **Step 1: Write the failing tests**

```kotlin
// gateway/app/src/test/kotlin/splice/app/cli/ConfigRepairTest.kt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.app.cli.ConfigRepair
import splice.core.topology.ConfigFinding
import java.nio.file.Path

private val FINDING = ConfigFinding("moonshot", "auth.kind", "\"api-kee\" is not a kind…", "one of: api-key, client, kimi-oauth")

class ConfigRepairTest {

    @Test
    fun `detects exactly the harnesses whose binaries exist`() {
        val r = repair(binaries = setOf("claude", "codex"))
        assertEquals(listOf("claude", "codex"), r.detectHarnesses().map { it.binary })
    }

    @Test
    fun `the repair prompt carries the config path, every finding, and its fix`() {
        val p = repair(binaries = setOf("claude")).repairPrompt(Path.of("/tmp/splice.toml"), listOf(FINDING))
        assertTrue(p.contains("/tmp/splice.toml"))
        assertTrue(p.contains("api-kee") && p.contains("api-key, client, kimi-oauth"))
        assertTrue(p.contains("do not change any other key"))
    }

    @Test
    fun `interactive yes launches the chosen harness with the prompt as argv`() {
        val execs = mutableListOf<List<String>>()
        val r = repair(binaries = setOf("claude"), tty = true, input = "1", onExec = execs::add)
        r.offer(Path.of("/tmp/splice.toml"), listOf(FINDING))
        assertEquals("claude", execs.single().first())
        assertTrue(execs.single().last().contains("api-kee"))
    }

    @Test
    fun `non-tty prints the command instead of launching`() {
        val execs = mutableListOf<List<String>>()
        val printed = StringBuilder()
        val r = repair(binaries = setOf("claude"), tty = false, onExec = execs::add, onOut = { printed.appendLine(it) })
        r.offer(Path.of("/tmp/splice.toml"), listOf(FINDING))
        assertTrue(execs.isEmpty())
        assertTrue(printed.toString().contains("claude \""))
    }

    @Test
    fun `no harness installed degrades to findings plus a plain-editor hint`() {
        val printed = StringBuilder()
        repair(binaries = emptySet(), onOut = { printed.appendLine(it) }).offer(Path.of("/t.toml"), listOf(FINDING))
        assertTrue(printed.toString().contains("api-kee"))
        assertTrue(printed.toString().contains("/t.toml"))
    }
}

private fun repair(
    binaries: Set<String>,
    tty: Boolean = false,
    input: String? = null,
    onExec: (List<String>) -> Unit = {},
    onOut: (String) -> Unit = {},
) = ConfigRepair(
    binaryExists = { it in binaries },
    isTty = { tty },
    out = onOut,
    readLine = { input },
    exec = { argv -> onExec(argv); 0 },
)
```

- [ ] **Step 2: Run to verify failure** — `./gradlew :app:test --tests "splice.app.cli.ConfigRepairTest" --console=plain`. Expected: compile error.

- [ ] **Step 3: Implement ConfigRepair**

```kotlin
// gateway/app/src/main/kotlin/splice/app/cli/ConfigRepair.kt
// NEW: the repair half of fail-closed boot (operator decision 2026-08-15). A refused config is
// reported with every finding, then — when a coding harness is installed and we are on a TTY —
// the operator is offered an interactive session pre-loaded with a surgical repair prompt. All
// effects are injected (binary lookup, TTY, exec) so every path is a unit test.
package splice.app.cli

import splice.core.topology.ConfigFinding
import java.nio.file.Path

public data class Harness(val name: String, val binary: String)

public class ConfigRepair(
    private val binaryExists: (String) -> Boolean,
    private val isTty: () -> Boolean,
    private val out: (String) -> Unit,
    private val readLine: () -> String?,
    private val exec: (List<String>) -> Int,
) {
    public fun detectHarnesses(): List<Harness> = KNOWN_HARNESSES.filter { binaryExists(it.binary) }

    public fun repairPrompt(configPath: Path, findings: List<ConfigFinding>): String = buildString {
        appendLine("My splice gateway config at $configPath was refused at boot with these findings:")
        findings.forEach { appendLine("- [providers.${it.providerKey}] ${it.key}: ${it.problem} (fix: ${it.fix})") }
        appendLine(
            "Please open that file, apply exactly these fixes, and do not change any other key. " +
                "Then run `splice doctor` to confirm the config validates clean.",
        )
    }

    /** Print findings; then offer the interactive fix when possible, else print the command. */
    public fun offer(configPath: Path, findings: List<ConfigFinding>) {
        findings.forEach { out("[providers.${it.providerKey}] ${it.key}: ${it.problem} — fix: ${it.fix}") }
        val harnesses = detectHarnesses()
        if (harnesses.isEmpty()) {
            out("Fix the keys above in $configPath and restart.")
            return
        }
        val prompt = repairPrompt(configPath, findings)
        if (!isTty()) {
            harnesses.forEach { out("To fix interactively: ${it.binary} \"${prompt.replace("\"", "\\\"")}\"") }
            return
        }
        harnesses.forEachIndexed { i, h -> out("  ${i + 1}) fix with ${h.name} (${h.binary})") }
        out("  Enter a number to start an interactive fix session, anything else to skip:")
        val choice = readLine()?.trim()?.toIntOrNull()?.let { harnesses.getOrNull(it - 1) } ?: return
        exec(listOf(choice.binary, prompt))
    }

    private companion object {
        val KNOWN_HARNESSES = listOf(
            Harness("Claude Code", "claude"),
            Harness("Kimi Code", "kimi"),
            Harness("Codex", "codex"),
            Harness("Grok", "grok"),
        )
    }
}
```

- [ ] **Step 4: Run the tests** — expected PASS.

- [ ] **Step 5: Wire production call sites.** (a) `Main.kt` boot path: catch the `IllegalArgumentException` from Task 2's `require` — better: have Daemon throw a typed `TopologyValidationException(val findings: List<ConfigFinding>)` (adjust Task 2's guard to throw it) — and call `ConfigRepair(...real effects...).offer(TopologyLoader.configPath(), e.findings)` before exiting non-zero. Real effects: `binaryExists = { name -> System.getenv("PATH").orEmpty().split(':').any { Path.of(it, name).toFile().canExecute() } }`, `isTty = { System.console() != null }`, `out = ::println`, `readLine = { readlnOrNull() }`, `exec = { argv -> ProcessBuilder(argv).inheritIO().start().waitFor() }`. (b) `DoctorCommand.kt`: after its existing parse probe, run `TopologyValidator.validate` and print findings through the same `offer` (doctor is interactive by nature). Follow doctor's existing probe/report idiom — read `DoctorProbes.kt` first.

- [ ] **Step 6: Run module tests** — `./gradlew :app:test --console=plain`. Expected: PASS.

- [ ] **Step 7: Ledger + commit** — `feat(cli): offer an interactive harness session to repair a refused config (HD-3)`.

---

### Task 4: Boot transparency — one resolved-profile line per head (HD-4)

Closes deferred item (b): no boot log of the resolved passthrough profile made every quirk-misread invisible.

**Files:**
- Modify: `gateway/app/src/main/kotlin/splice/app/Daemon.kt` (head assembly point — where `passthroughProvider`/head wiring completes)
- Test: `gateway/app/src/test/kotlin/splice/app/PassthroughQuirksOverlayTest.kt` (it already constructs Daemons with an injectable `log`)

**Interfaces:**
- Consumes: `resolvedProfile(cfg)` (Task 1), `Daemon`'s existing `log: (String) -> Unit` constructor param.
- Produces: a log line per assembled passthrough head, format pinned by test: `head <headKey>: dialect=anthropic-passthrough auth=<kind> profile=<wire> quirk-overrides=[<toml keys set>] static-headers=[<names only>]`.

- [ ] **Step 1: Write the failing test** — in `PassthroughQuirksOverlayTest.kt`, capture `log` lines into a list when assembling the kimi fixture and the neutral fixture; assert:

```kotlin
    @Test
    fun `boot logs one greppable profile line per passthrough head`() {
        // assemble with log = lines::add (the file's Daemon harness already injects log)
        assertTrue(lines.any { it.contains("profile=kimi") && it.contains("auth=kimi-oauth") })
        assertTrue(lines.none { it.contains("static-headers=") && it.contains("2023-06-01") }) // names only, never values
    }
```

- [ ] **Step 2: Run to verify failure.** Expected: assertion failure (no such line).

- [ ] **Step 3: Implement** — at the point in `Daemon` where the passthrough head's provider is fully resolved, emit through the existing `log`:

```kotlin
        log(
            "head $headKey: dialect=anthropic-passthrough auth=${cfg.auth.kind} profile=${profile.wire} " +
                "quirk-overrides=${declaredQuirkKeys(cfg.quirks)} static-headers=${staticHeaders.keys.sorted()}",
        )
```

`declaredQuirkKeys` = the passthrough knob names whose `QuirksConfig` value is non-null (reuse the key list from Task 2's `PASSTHROUGH_ONLY_QUIRKS` — it lives in core, importable from app). Header NAMES only — a value could be a credential-shaped default.

- [ ] **Step 4: Run tests** — `./gradlew :app:test --console=plain`. Expected: PASS.

- [ ] **Step 5: Ledger + commit** — `feat(app): boot-log the resolved profile per passthrough head (HD-4)`.

---

### Task 5: Forwarded-header fidelity — repeated anthropic-beta (HD-5)

Closes deferred items (e) and (f): `call.request.headers[name]` at `HeadServer.kt:406` takes the FIRST value, so a caller legally sending two `anthropic-beta` lines loses the second; and caller-wins-entirely semantics for the forwarded set were never pinned.

**Files:**
- Modify: `gateway/gateway/src/main/kotlin/splice/gateway/head/HeadServer.kt:405-408` (`forwardedClientHeaders`)
- Test: `gateway/gateway/src/test/kotlin/head/HeadServerClientAuthTest.kt`

**Interfaces:**
- Consumes: `HeadDeps.FORWARDED_CLIENT_HEADERS` (existing), the `BuiltTurn.extraHeaders: Map<String, String>` channel (existing — stays single-valued).
- Produces: capture behavior later tasks rely on: `anthropic-beta` = all inbound values comma-joined (RFC 9110: repeated list-valued field lines ≡ comma-join); every other forwarded header = first value.

- [ ] **Step 1: Write the failing test** — in `HeadServerClientAuthTest.kt` (use the Task boundaries idiom already in the file):

```kotlin
    @Test
    fun `repeated anthropic-beta lines are forwarded complete, comma-joined`() {
        val port = startHead(forwardClientAuth = true)
        val before = upstream.requests.size
        val (status, _) = runBlocking {
            val response = client.post("http://127.0.0.1:$port/v1/messages") {
                header("Authorization", "Bearer caller-own-token")
                header("anthropic-beta", "oauth-2025-04-20")
                header("anthropic-beta", "interleaved-thinking-2025-05-14")
                header("Content-Type", "application/json")
                setBody(
                    """{"model":"claude-splice--claude-fable-5","max_tokens":16,""" +
                        """"messages":[{"role":"user","content":"hi"}],"stream":true}""",
                )
            }
            response.status to response.bodyAsText()
        }
        assertEquals(HttpStatusCode.OK, status)
        assertEquals(before + 1, upstream.requests.size)
        assertEquals(
            listOf("oauth-2025-04-20,interleaved-thinking-2025-05-14"),
            upstream.requests[before]["anthropic-beta"].orEmpty(),
        )
    }
```

(The existing `turn` helper takes a `Map` and cannot express a repeated header — inline the client call as above.)

- [ ] **Step 2: Run to verify failure** — `./gradlew :gateway:test --tests HeadServerClientAuthTest --console=plain`. Expected: FAIL — only the first beta arrives.

- [ ] **Step 3: Implement** — replace `forwardedClientHeaders`:

```kotlin
    private fun forwardedClientHeaders(call: ApplicationCall): Map<String, String> =
        HeadDeps.FORWARDED_CLIENT_HEADERS.mapNotNull { name ->
            val values = call.request.headers.getAll(name)?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            // anthropic-beta is list-valued: repeated field lines are semantically the comma-join
            // (RFC 9110 §5.3), and the extraHeaders channel is single-valued — join, never truncate.
            // The rest of the allowlist is single-valued by nature (a repeated Authorization is
            // malformed); first value, as before.
            val value = if (name.equals("anthropic-beta", ignoreCase = true)) values.joinToString(",") else values.first()
            name to value
        }.toMap()
```

- [ ] **Step 4: Pin caller-wins-entirely for the set-valued header** — add alongside (this is deferred item (f) resolved BY LAW rather than by merge: NATIVE-AUTH says forward *verbatim*, so a caller's beta set REPLACES any configured default; the configured default rides only when the caller sends none — already proven for anthropic-version by the existing `configured default still rides` test; add the beta twin):

```kotlin
    @Test
    fun `a caller's beta set replaces a configured beta default entirely`() {
        // startHead variant whose staticHeaders include "anthropic-beta" to "config-default-beta"
        // caller sends anthropic-beta: caller-choice -> upstream sees exactly ["caller-choice"], never the default
        // caller sends none                        -> upstream sees exactly ["config-default-beta"]
    }
```

Implement the `startHead` variant by adding a `staticHeaders: Map<String, String>` parameter with the current map as default.

- [ ] **Step 5: Run tests** — `./gradlew :gateway:test --console=plain`. Expected: PASS.

- [ ] **Step 6: Ledger + commit** — `fix(gateway): forward repeated anthropic-beta complete, pin caller-wins (HD-5)`.

---

### Task 6: Caller-fault health attribution (HD-6)

Closes deferred item (d): on a client-auth head, a caller forwarding no/bad credential 401s upstream and today charges `health.provider()` — `/api/heads` reads as "Anthropic is failing".

**Files:**
- Modify: `gateway/gateway/src/main/kotlin/splice/gateway/head/HeadHealthCounters.kt`
- Modify: `gateway/gateway/src/main/kotlin/splice/gateway/head/TurnDriver.kt:339` region (the `UpstreamFailed` arm of `emitFailure`)
- Modify: `gateway/control/src/main/kotlin/splice/control/ControlServer.kt:489` region (the `/api/heads` health payload)
- Test: `gateway/gateway/src/test/kotlin/head/HeadServerClientAuthTest.kt`, plus the existing ControlServer test that pins the `/api/heads` payload (find it via `grep -rn "healthSnapshot\|api/heads" gateway/control/src/test`).

**Interfaces:**
- Consumes: `deps.forwardClientAuth` (existing on `HeadDeps`), `UpstreamFailureClassifier` verdict (`failure.type`).
- Produces: `HeadHealthCounts(localOrigin: Long, providerError: Long, callerOrigin: Long)`; `HeadHealthCounters.caller()`; `/api/heads` health object gains `callerOrigin`.

- [ ] **Step 1: Write the failing test** — in `HeadServerClientAuthTest.kt`: make `RecordingUpstream` respond 401 for a request whose Authorization contains `"reject-me"` (extend the handler: `if (headers["authorization"]?.any { it.contains("reject-me") } == true) { respond 401 with an authentication_error JSON body }`), then:

```kotlin
    @Test
    fun `a caller's rejected credential charges the caller bucket, not the provider`() {
        val port = startHead(forwardClientAuth = true)
        turn(port, mapOf("Authorization" to "Bearer reject-me"))
        val h = heads.last().healthSnapshot()
        assertEquals(1, h.counts.callerOrigin)   // adjust to HeadHealth's actual shape at HeadServer.kt:228
        assertEquals(0, h.counts.providerError)
    }
```

Read `HeadServer.healthSnapshot()` (`HeadServer.kt:228`) first and use its real field path.

- [ ] **Step 2: Run to verify failure** — compile error on `callerOrigin`.

- [ ] **Step 3: Implement** — `HeadHealthCounters` gains `private val callerOrigin = AtomicLong(0)`, `fun caller() { callerOrigin.incrementAndGet() }`, snapshot/reset extended; `HeadHealthCounts` gains `val callerOrigin: Long`. In `TurnDriver.emitFailure`'s `UpstreamFailed` arm replace the unconditional `health.provider()`:

```kotlin
                // On a forward-mode head an upstream AUTH rejection is the CALLER's credential being
                // judged — splice sent nothing of its own, and counting it against the provider makes
                // a healthy Anthropic read as failing. Every other failure class stays provider-attributed.
                if (failure.type == ErrorType.AUTHENTICATION && deps.forwardClientAuth) health.caller() else health.provider()
```

Then follow the compile errors: `ControlServer.kt:489` adds `callerOrigin` to the health JSON it builds (additive field — the dashboard ignores unknown fields; verify the webui contract test `gateway/control/src/test/kotlin/splice/control/WebuiContractTest.kt` still passes and extend its expectation if it pins the payload shape).

- [ ] **Step 4: Run tests** — `./gradlew :gateway:test :control:test --console=plain`. Expected: PASS.

- [ ] **Step 5: Ledger + commit** — `feat(gateway): attribute forwarded-credential 401s to a caller health bucket (HD-6)`.

---

### Task 7: Ambient-credential warning + forwarded-name telemetry (HD-7)

Closes deferred item (c): an ambient `ANTHROPIC_API_KEY` survives the native-auth launch recipe (deliberately — it may BE the credential) and can silently bill pay-per-token instead of the subscription. Two visibility fixes, no behavior change.

**Files:**
- Modify: `gateway/control/src/main/kotlin/splice/control/LaunchService.kt:92-115` (recipe warning)
- Modify: `gateway/gateway/src/main/kotlin/splice/gateway/head/HeadServer.kt:390` region (forwarded-names log)
- Test: `gateway/control/src/test/kotlin/splice/control/LaunchServiceTest.kt`, `gateway/gateway/src/test/kotlin/head/HeadServerClientAuthTest.kt`

**Interfaces:**
- Consumes: `LaunchService`'s existing `envReader: (String) -> String?` and `LaunchRecipe(env, unset, argv, warning)`; `HeadDeps.log` (existing).
- Produces: `LaunchRecipe.warning` non-null when `nativeClientAuth` and ambient `ANTHROPIC_API_KEY` is set (composes with the existing dangerous-perms warning via newline); one `deps.log` line per forwarded turn naming forwarded header NAMES only.

- [ ] **Step 1: Write the failing launch test** — in `LaunchServiceTest.kt` (mirror its existing recipe fixtures; it injects `envReader`):

```kotlin
    @Test
    fun `a native-auth launch warns when an ambient ANTHROPIC_API_KEY could outbid the subscription`() {
        val recipe = buildRecipe(nativeClientAuth = true, envReader = { if (it == "ANTHROPIC_API_KEY") "sk-ambient" else null })
        assertTrue(recipe.warning.orEmpty().contains("ANTHROPIC_API_KEY"))
        assertFalse(recipe.warning.orEmpty().contains("sk-ambient"))  // name the VAR, never the value
    }

    @Test
    fun `no ambient key, no warning; foreign heads never warn about it`() {
        assertNull(buildRecipe(nativeClientAuth = true, envReader = { null }).warning)
        assertNull(buildRecipe(nativeClientAuth = false, envReader = { "sk-ambient" }).warning)
    }
```

(Use the file's existing spec/recipe builder helper; `nativeClientAuth = false` also asserts nothing regressed for foreign heads, whose recipe UNSETS the var — the warning would be noise.)

- [ ] **Step 2: Run to verify failure.**

- [ ] **Step 3: Implement** — in `buildRecipe`'s warning assembly:

```kotlin
        val ambientKeyWarning = if (spec.nativeClientAuth && envReader("ANTHROPIC_API_KEY") != null) {
            "ANTHROPIC_API_KEY is set in this environment and survives a native-auth launch — " +
                "Claude Code will prefer it over the subscription login and bill pay-per-token. " +
                "Unset it before launching if that is not intended."
        } else {
            null
        }
        val warning = listOfNotNull(permsWarning, ambientKeyWarning).joinToString("\n").ifEmpty { null }
```

(where `permsWarning` is the existing dangerous-perms string, refactored to a local val).

- [ ] **Step 4: Forwarded-names telemetry** — in `HeadServer` where `forwardedClientHeaders(call)` is merged (line ~390):

```kotlin
        val forwarded = forwardedClientHeaders(call)
        if (forwarded.isNotEmpty()) deps.log("forwarded-client-auth: ${forwarded.keys.sorted().joinToString(", ")}")
```

Names only — the values are credentials. Test in `HeadServerClientAuthTest`: capture `log` lines in `startHead` (add `log = logLines::add` to its `HeadDeps`), assert a turn with Authorization + anthropic-beta produces a line containing both names and NOT the token value.

- [ ] **Step 5: Run tests** — `./gradlew :control:test :gateway:test --console=plain`. Expected: PASS.

- [ ] **Step 6: Ledger + commit** — `feat(control): warn on ambient ANTHROPIC_API_KEY at native-auth launch; log forwarded header names (HD-7)`.

---

### Task 8: Builder invariant comment (HD-8)

Closes deferred item (i): four unconditional builder transforms are not listed in `PassthroughRequestBuilder`'s own invariant comment, so a reader auditing "neutral = faithful" trusts a lie of omission.

**Files:**
- Modify: `gateway/dialect-anthropic-passthrough/src/main/kotlin/splice/dialect/passthrough/PassthroughRequestBuilder.kt` (file-header invariant comment only)

- [ ] **Step 1: Verify the four claims against the code** (read the builder end-to-end first — §5): (1) a `thinking` config with `type != "enabled"` is dropped; (2) an empty thinking block is dropped; (3) non-array `messages` content is coerced; (4) the effort ladder populates `TurnMeta.effort` even with `mapThinkingToAdaptive` off. **Any claim the code does not support is reported to the orchestrator, not documented** (premise-check duty).

- [ ] **Step 2: Extend the header comment** — under the existing invariant text add:

```kotlin
// UNCONDITIONAL even on a NEUTRAL profile (the four transforms every head gets — "faithful
// passthrough" means faithful MODULO these, each load-bearing for the wire contract):
//   1. a disabled thinking config is dropped, never forwarded;
//   2. an empty unsigned thinking block is dropped;
//   3. non-array message content is coerced to the array form;
//   4. the effort ladder populates TurnMeta.effort even when the adaptive mapping is off.
```

(reword each line to match what Step 1 actually verified, citing the function names involved).

- [ ] **Step 3: Verify** — `./gradlew :dialect-anthropic-passthrough:check --console=plain` (comment-only change; check covers lint). Goldens untouched.

- [ ] **Step 4: Ledger + commit** — `docs(dialect): name the four unconditional builder transforms in the invariant comment (HD-8)`.

---

### Task 9: Arch enforcement — make the fences compile (HD-9)

The #924 capstone: the boundaries Tasks 1–2 drew become konsist laws so an agent-authored regression is a build error, not a review comment.

**Files:**
- Modify: `gateway/arch-tests/src/test/kotlin/ArchitectureLawsTest.kt`

**Interfaces:**
- Consumes: konsist DSL already in the file (`Konsist.scopeFromProject()` etc. — mirror the existing laws' scope helpers exactly).

- [ ] **Step 1: Write the two laws (they should pass immediately — prove they can fail before trusting them)**

```kotlin
    @Test
    fun `PassthroughQuirks is constructed only in its own module, the profile registry, and Daemon`() {
        // scope: production sources; find call sites of "PassthroughQuirks(" constructor invocations
        // allowed files: anything under dialect-anthropic-passthrough/src/main, plus Daemon.kt
        // assertion: no other production file constructs it (tests are exempt)
    }

    @Test
    fun `dialect modules import no splice-core-topology type`() {
        // scope: files under */dialect-*/src/main
        // assertion: no import starting with "splice.core.topology" — the assembly point (Daemon)
        // owns all TOML->dialect mapping (the chatQuirks idiom, Daemon.kt:238)
    }
```

Write both with the file's real konsist idiom (read its 17+ existing laws first; reuse its scope constants).

- [ ] **Step 2: RED PROOF — synthetic violations** (walls-first law): scratch-edit a dialect file to add `import splice.core.topology.Topology` → law 2 FAILS; scratch-construct `PassthroughQuirks("x")` in `gateway/gateway/src/main/.../HeadServer.kt` → law 1 FAILS. `git checkout` both scratch edits, laws green again. Record both commands in the ledger note.

- [ ] **Step 3: Run** — `./gradlew :arch-tests:test --console=plain`. Expected: PASS.

- [ ] **Step 4: Ledger + commit** — `test(arch): konsist laws fence PassthroughQuirks construction and dialect-topology imports (HD-9)`.

---

### Task 10: Full migration sweep (HD-10)

The style-law capstone: every gateway main-source file NOT already cleaned by Tasks 1–9 is refactored to zero top-level functions and zero companion objects. Runs after Tasks 1–9, before close-out.

**Files:** every `gateway/*/src/main/**/*.kt` still containing a violation (enumerate at dispatch time with `grep -rlE "^(public |private |internal )?(suspend )?fun |companion object" --include="*.kt" */src/main`). Baseline at law time: 336 top-level funs + 58 companions across 105/151 files.

**Rules per file:** behavior-preserving ONLY — no signature drift visible to other modules beyond mechanical relocation, kimi goldens byte-identical, detekt budgets met by decomposition into cohesive injected/helper classes (never suppression), each file fully clean in ONE edit (strict hooks refuse anything less). Public top-level API consumed across modules moves to a named home and every consumer updates in the same slice; slices stay compile-green.

- [ ] **Step 1:** Enumerate remaining violating files; group into module-sized slices (one implementer per slice, sequential when slices share consumers).
- [ ] **Step 2:** Per slice: dispatch implementer (migrate + run the owning module tests + `./gradlew check`), then task review as usual.
- [ ] **Step 3:** Final: both grep counts hit 0 (`fun` pattern and `companion object` over `*/src/main`), whole-gateway `check` green, goldens diff empty.
- [ ] **Step 4:** Ledger + commit per slice.

### Task 11: Close-out (orchestrator only)

- [ ] **Step 1:** every HD item `done` → orchestrator independently re-runs each item's verify, reads each diff, sets `verified` via `$M set-status <ID> verified`.
- [ ] **Step 2:** `npm run gate` on the final tip — GATE: PASS with the tip hash printed beside the result in the same command block; record as an `$M note` on the last item.
- [ ] **Step 3:** `git push` (orchestrator's single publication act). PR #99 already carries the branch; these commits ride the same PR unless the operator says otherwise.

---

## Self-Review (performed at write time)

- **Coverage vs the recorded boundaries:** (a) profile coupling → Tasks 1+2; typo'd kind → Task 2; (b) boot log → Task 4; (c) ambient key → Task 7; (d) health attribution → Task 6; (e)/(f) header fidelity → Task 5; (g) inert knobs → Task 2; (h) header names → Task 2; (i) invariant comment → Task 8; #924 structural fences → Task 9; operator repair-flow decision → Task 3. No orphan boundary.
- **Known unknowns named where they sit:** exact helper names in `TopologyConfigOverridesTest` / `PassthroughQuirksOverlayTest` / `LaunchServiceTest` fixtures, `HeadHealth`'s field path, and the konsist idiom — each task instructs reading the named neighbor first and gives the assertions to keep.
- **Type consistency:** `ConfigFinding(providerKey, key, problem, fix)` used identically in Tasks 2 and 3; `PassthroughProfile.{wire, baseHeaders, usesDeviceIdentity, quirksBase, fromWire}` used identically in Tasks 1, 2 (wire-set lockstep comment), and 4; `HeadHealthCounts.callerOrigin` in Task 6 only.
- **Status-quo proofs:** goldens (Task 1 Step 10), example-config-validates-clean (Task 2 Step 3), foreign-head recipe unchanged (Task 7 Step 1), existing kimi overlay tests kept green (Task 1 Step 8).
