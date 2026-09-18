// NEW: Konsist architecture laws (P1-KONSIST) — ring 3 of the enforcement stack.
// These arm as code lands: an empty scope passes vacuously, a violation fails :arch-tests:test.
// Grow this file as modules land; every new law gets a red/green proof in the ledger note.
import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.verify.assertTrue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File

/** Production modules whose every .kt file must open with a slot header (#963):
 *  `// PORT-OF: <source> @ <sha> — invariants: ...` or `// NEW: <reason>`.
 *
 *  DR-165: this list is no longer the DENOMINATOR, only the covered set. It used to be both, and a
 *  denominator taken from the list being checked cannot fail for anything absent from that list —
 *  the same tautology the module-direction law below already avoids by reading settings.gradle.kts.
 *  `fir-checks` was the live proof: it ships three production files and was simply not here, so
 *  neither this law nor the contract-coverage law had ever looked at it. */
private val PORT_SCOPE_MODULES = listOf(
    "core", "provider-spi", "dialect-openai-responses", "dialect-openai-chat",
    "dialect-anthropic-passthrough", "provider-codex", "provider-grok", "provider-openai",
    "provider-kimi", "provider-muse", "gateway", "control", "app", "fir-checks",
)

/** DR-165: modules that ship production Kotlin and are deliberately OUT of the slot-header law,
 *  each with a written reason. Empty today, and that is the honest state — `fir-checks` is the only
 *  harness with production sources and its files already carried slot headers, so it is covered
 *  above rather than excused here. The mechanism exists so a future exclusion has somewhere to go
 *  that carries a REASON: a module in neither map fails by name, and a blank reason fails too,
 *  because a placeholder is an absence wearing a label. */
private val SLOT_HEADER_EXEMPT: Map<String, String> = emptyMap()

/** DR-165: every module that ACTUALLY ships production Kotlin, read off the tree. This is the
 *  denominator both coverage laws now use — the module-direction law already derives its own from
 *  settings.gradle.kts, and these two were the stragglers still trusting a hand-authored list. */
private fun productionModules(root: File): Set<String> =
    root.listFiles().orEmpty()
        .filter { it.isDirectory }
        .filter { module ->
            val main = File(module, "src/main/kotlin")
            main.isDirectory && main.walkTopDown().any { it.isFile && it.extension == "kt" }
        }
        .map { it.name }
        .toSet()

/** DR-165: the disposition of every production module, as violation lines. PURE so it can be proven
 *  against synthetic input — with the live tree fully dispositioned, nothing real can red it, which
 *  is exactly the unfalsifiable shape this row exists to remove. */
private fun slotHeaderDispositions(
    onDisk: Set<String>,
    covered: Set<String>,
    exempt: Map<String, String>,
): List<String> {
    val violations = mutableListOf<String>()
    (onDisk - covered - exempt.keys).sorted().forEach { module ->
        violations += "$module ships production Kotlin but is in neither PORT_SCOPE_MODULES nor " +
            "SLOT_HEADER_EXEMPT — cover it (preferred) or exempt it WITH a written reason."
    }
    exempt.filterValues { it.isBlank() }.keys.sorted().forEach { module ->
        violations += "$module is exempted from the slot-header law with a blank reason — a placeholder " +
            "is an absence wearing a label; write why, or cover the module."
    }
    (covered - onDisk).sorted().forEach { module ->
        violations += "PORT_SCOPE_MODULES names $module, which ships no production Kotlin — drop the " +
            "entry; it currently governs nothing."
    }
    (exempt.keys - onDisk).sorted().forEach { module ->
        violations += "SLOT_HEADER_EXEMPT names $module, which ships no production Kotlin — drop the " +
            "entry; it currently exempts nothing."
    }
    return violations
}

/** DR-165: one *RequestBuilder module's contract verdict, or null when it is fully covered. PURE for
 *  the same reason as [slotHeaderDispositions]: every live builder module already ships both halves,
 *  so the tree cannot red the CONSUMER half, and an addition the tree cannot falsify would be the
 *  exact defect this row repairs. The fixture below is what makes it real. */
private fun contractViolation(module: String, hasFixture: Boolean, hasConsumer: Boolean): String? = when {
    !hasFixture -> "$module ships a *RequestBuilder but no src/test/resources/contract/<name>.json"
    !hasConsumer ->
        "$module has a contract fixture but no *ContractTest.kt reading it — a golden " +
            "nothing compares against pins nothing"
    else -> null
}

/** Every dialect module — what :gateway is allowed to know about, and what a provider picks from. */
private val DIALECTS = setOf(
    ":dialect-anthropic-passthrough",
    ":dialect-openai-responses",
    ":dialect-openai-chat",
)

/** The domain plus the provider contract: what every adapter (dialect, provider, transport) starts from. */
private val ADAPTER_BASE = setOf(":core", ":provider-spi")

/** Ports-and-adapters dependency direction (HD-11): module -> the internal modules it may depend on
 *  in a TEST configuration.
 *
 *  V4-91 (2026-09-17) NARROWED THIS MAP TO THE TEST PLANE. It used to govern "ANY Gradle
 *  configuration — main OR test", which meant its main-plane half was a hand COPY of `moduleLaw` in
 *  build-logic/src/main/kotlin/splice.module-law.gradle.kts with nothing comparing the two. Main
 *  edges are now graded against that file, PARSED ([ModuleLawFile]) — the same map that actually
 *  fails the build at configuration time — and [lawDriftViolations] fails when this map and the
 *  build's stop describing one architecture. Two hand-authored maps agreeing with each other and
 *  disagreeing with the build is the tautology DR-165 removed from the slot-header law.
 *
 *  The test plane still needs its OWN map, and that is why this one survives: the Gradle plugin
 *  deliberately exempts test configurations — "integration tests legitimately wire sibling modules"
 *  — but the exemption is blanket, and every inverted edge this repo actually has lives inside it.
 *  :gateway tests reaching a DIALECT is legitimate and stays legal here, while a provider reaching
 *  back into :gateway is an inversion whether the wire is in main or in test. The entries therefore
 *  remain SUPERSETS of the main allowances (a main dependency is on the test compile classpath by
 *  construction), which is the third property lawDriftViolations checks.
 *
 *  Edges come from the build files, not konsist's package model: these are GRADLE-module edges, and
 *  a module can depend on another before any file imports it.
 *
 *  Modules absent from this map are unrestricted — see UNRESTRICTED_MODULES; a module that is in
 *  neither is a failure, because silent absence is how a rule set stops applying. */
private val MODULE_DEPENDENCY_LAW: Map<String, Set<String>> = mapOf(
    // the domain. Depends on nothing internal, forever.
    ":core" to emptySet(),
    // the provider contract. Speaks the domain and nothing else.
    ":provider-spi" to setOf(":core"),
    // a dialect adapts the contract to one wire format.
    ":dialect-anthropic-passthrough" to ADAPTER_BASE,
    ":dialect-openai-responses" to ADAPTER_BASE,
    ":dialect-openai-chat" to ADAPTER_BASE,
    // a provider speaks its own dialect(s) — never another provider, never the transport.
    ":provider-codex" to ADAPTER_BASE + ":dialect-openai-responses",
    ":provider-grok" to ADAPTER_BASE + ":dialect-openai-responses",
    ":provider-kimi" to ADAPTER_BASE + ":dialect-anthropic-passthrough",
    ":provider-muse" to ADAPTER_BASE + ":dialect-anthropic-passthrough",
    ":provider-openai" to ADAPTER_BASE + setOf(":dialect-openai-responses", ":dialect-openai-chat"),
    // the transport serves any dialect; it must not know a CONCRETE provider (that is :app's job).
    ":gateway" to ADAPTER_BASE + DIALECTS,
    // the management plane reads the domain only.
    ":control" to setOf(":core"),
)

/** Exempt from the direction law: :app is the composition root and may wire anything, and the rest are
 *  harnesses rather than product layers — the same set splice.module-law.gradle.kts calls `nonLibrary`. */
private val UNRESTRICTED_MODULES = setOf(":app", ":spikes", ":arch-tests", ":fir-checks")

/** V4-91 (audit A rows 3, 10, 11): the two OS escapes :core may not reach for — SPAWNING A
 *  PROCESS and OPENING A NETWORK CONNECTION.
 *
 *  `core stays framework-free` above bans `io.ktor`, which is the FRAMEWORK half. This is the
 *  OPERATING-SYSTEM half: the domain describes what a turn is, and a domain that forks a child or
 *  dials a socket has stopped being a domain — the same reason the module law lets :core depend on
 *  nothing internal and on nothing outside kotlin/kotlinx.
 *
 *  NARROWED 2026-09-17 (review REDO). The first revision banned the java.net and java.nio.channels
 *  PACKAGES wholesale, which is a different and wrong law. What it actually named:
 *   · `java.net.URI` (core/topology/LocalProviderRule.kt) — a VALUE type. Parsing a base URL to ask
 *     whether it is loopback performs no I/O at all; banning it says nothing about the OS.
 *   · `java.nio.channels.FileChannel` / `FileLock` / `OverlappingFileLockException`
 *     (core/config/KeyStore.kt, core/util/JsonlSink.kt) — FILE I/O, which :core already owns openly
 *     through `java.nio.file` in sixty-odd files. A law that allows `Files.write` and forbids the
 *     file LOCK that makes it safe is not protecting a boundary, it is moving one arbitrarily.
 *   · `java.net.SocketException` / `UnknownHostException` (core/util/SafeFailureText.kt, as FQNs in
 *     a `when`) — EXCEPTION types. Classifying a failure someone else's socket produced is the
 *     domain's job; it opens nothing.
 *  All four are ALLOWED, deliberately and with this paragraph as the reason. The law is the
 *  audit's: no child processes, no network.
 *
 *  THREE MATCHERS, because the escapes are spelled three ways, and the spread is the whole reason
 *  an import denylist alone would have passed:
 *   · PREFIXES — `java.net.http.*` is the JDK HTTP client; every member of it is network I/O, so
 *     the package goes wholesale.
 *   · EXACT IMPORTS — the java.net types that actually open or resolve something. Named one by one
 *     rather than by package, because that is the only way `URI` and the exception types stay
 *     legal. A java.net type not on this list is allowed; adding one is a dated edit here.
 *   · REFERENCES — matched against the SOURCE, because `java.lang` is imported IMPLICITLY in every
 *     Kotlin file. `ProcessBuilder(...)` and `Runtime.getRuntime()` NEVER appear in an import list,
 *     so a law that only read imports could never see
 *     core/src/main/kotlin/splice/core/launch/HookScriptFiles.kt:57 — the single locus the row that
 *     ordered this law names, and after the narrowing the only locus in the tree. The row said "no
 *     java.lang.ProcessBuilder ... imports"; there is no such import to ban, so the matcher had to
 *     be the reference instead.
 *
 *  A forbidden type is also caught when it is written FULLY QUALIFIED with no import
 *  (`java.net.Socket(...)` inline), which is why [coreEscapeViolations] makes a second pass over
 *  the non-import, non-comment lines. The remedy for every finding is the same: declare a port in
 *  :core and implement it in :app (mirror FileIoTask / DirectoryProbe). */
private val CORE_FORBIDDEN_IMPORT_PREFIXES = listOf("java.net.http.")

/** The java.net types the review's narrowing (2026-09-17) keeps banned, named one by one rather
 *  than by package because that is the only way `java.net.URI` (a value type), `java.net.SocketException`
 *  / `java.net.UnknownHostException` (exception types the domain classifies, not opens) and the
 *  datagram, name-resolution and proxy-selection types stay legal. A java.net type not on this list
 *  is allowed; adding one is a dated edit here. */
private val CORE_FORBIDDEN_IMPORTS = setOf(
    "java.net.Socket",
    "java.net.ServerSocket",
    "java.net.URL",
    "java.net.HttpURLConnection",
)

/** Bare type references banned in :core main — see CORE_FORBIDDEN_IMPORT_PREFIXES. The trailing
 *  `(` is required so a KDoc sentence ABOUT ProcessBuilder is not a violation: HookScriptFiles'
 *  own comment on line 43 says "ProcessBuilder's IOException", which is prose and not an escape.
 *  `Runtime.exec` is an instance method reachable only through the `Runtime.getRuntime()` factory,
 *  so the matcher spells the factory; the two are the one child-process escape. */
private val CORE_FORBIDDEN_REFERENCES = listOf("ProcessBuilder(", "Runtime.getRuntime(")

/** The fully-qualified form of each banned type, as a BOUNDED matcher, longest name first.
 *
 *  THE BOUNDARY IS THE POINT, and it was measured rather than reasoned: `java.net.SocketException`
 *  CONTAINS `java.net.Socket`, so a plain `contains` reported
 *  core/src/main/kotlin/splice/core/util/SafeFailureText.kt:18 — a `when` branch classifying an
 *  exception someone else's socket threw — as :core dialling out. The negative lookahead is what
 *  keeps an exception type, and any future `java.net.URLStreamHandlerFactory`-shaped name, out.
 *  Longest first for the same family of reasons: `java.net.URLConnection` contains `java.net.URL`,
 *  and a set has no iteration order, so an unsorted scan names a nondeterministic type for one
 *  violation — which is how a message assertion becomes flaky and then gets deleted. */
private val CORE_FORBIDDEN_FQN_USES: List<Pair<String, Regex>> =
    CORE_FORBIDDEN_IMPORTS.sortedByDescending { it.length }
        .map { it to Regex(Regex.escape(it) + "(?![A-Za-z0-9_])") }

/** V4-91: one file's :core escapes, as violation lines. PURE so it can be proven against synthetic
 *  input — the live tree can red it today, but the day the fix row lands, a silently-deleted
 *  matcher would look exactly like a clean tree. Same discipline as the DR-165 guards above. */
private fun coreEscapeViolations(path: String, text: String): List<String> {
    val violations = mutableListOf<String>()
    val remedy = "declare a port in :core and implement it in :app (mirror FileIoTask/DirectoryProbe)."
    text.lineSequence().forEachIndexed { index, line ->
        val trimmed = line.trim()
        if (!trimmed.startsWith("import ")) return@forEachIndexed
        val imported = trimmed.removePrefix("import ").trim().removeSuffix(";")
        val banned = CORE_FORBIDDEN_IMPORTS.contains(imported) ||
            CORE_FORBIDDEN_IMPORT_PREFIXES.any { imported.startsWith(it) }
        if (banned) {
            violations += "$path:${index + 1} imports $imported — :core opens no network; $remedy"
        }
    }
    // SECOND PASS: the same types written fully qualified with no import. Comment lines are skipped
    // so a sentence NAMING a banned type is prose, exactly as the reference matcher's `(` intends.
    text.lineSequence().forEachIndexed { index, line ->
        val trimmed = line.trim()
        if (trimmed.startsWith("import ") || trimmed.startsWith("//") ||
            trimmed.startsWith("*") || trimmed.startsWith("/*")
        ) {
            return@forEachIndexed
        }
        val hit = CORE_FORBIDDEN_FQN_USES.firstOrNull { it.second.containsMatchIn(line) }?.first
            ?: CORE_FORBIDDEN_IMPORT_PREFIXES.firstOrNull { line.contains(it) }
        if (hit != null) {
            violations += "$path:${index + 1} names $hit fully qualified — :core opens no network, " +
                "and skipping the import does not skip the law; $remedy"
        }
    }
    // Reference matching runs over the whole file rather than per line so a call split across
    // lines still counts; the `(` in the needle is what keeps prose out.
    CORE_FORBIDDEN_REFERENCES.filter { text.contains(it) }.forEach { reference ->
        val line = text.lineSequence().indexOfFirst { it.contains(reference) } + 1
        violations += "$path:$line uses ${reference.removeSuffix("(")} — :core spawns no process; " +
            "java.lang is imported implicitly, so no import denylist can see this. Declare a port " +
            "in :core and implement it in :app."
    }
    return violations
}

/** V4-91 (audit C row 4): the Gradle module law, PARSED — the single source of truth for
 *  MAIN-configuration edges.
 *
 *  Until this row, MODULE_DEPENDENCY_LAW below carried a hand COPY of
 *  build-logic/src/main/kotlin/splice.module-law.gradle.kts's `moduleLaw`, and nothing compared the
 *  two. Two maps that agree with each other and disagree with the build is the same tautology
 *  DR-165 removed from the slot-header law: a denominator taken from the list being checked cannot
 *  fail for anything absent from that list. So the main plane is now graded against the GRADLE map
 *  (which is also what actually fails the build, at configuration time), the Konsist map is the
 *  TEST plane only, and [lawDriftViolations] fails when the two stop lining up. */
private class ModuleLawFile(text: String) {
    private val stripped = text
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("//[^\n]*"), "")

    /** project path -> allowed MAIN-configuration project dependencies. */
    val mainLaw: Map<String, Set<String>> = Regex(
        // Escaped rather than raw: a raw string whose first content character is a quote is a
        // needlessly delicate lexing question, and this one starts with the key's opening quote.
        "\"(:[A-Za-z0-9._\\-]+)\"\\s+to\\s+(?:emptySet\\(\\)|setOf\\(([^)]*)\\))",
    ).findAll(mainLawBody(stripped)).associate { match ->
        match.groupValues[1] to MODULE_PATH_IN_SET
            .findAll(match.groupValues[2])
            .map { it.groupValues[0] }
            .toSet()
    }

    /** The modules the build exempts from explicitApi and from the law — Konsist's own
     *  UNRESTRICTED_MODULES comment already claims this is the same set, so drift is a defect. */
    val nonLibrary: Set<String> = namedSet(stripped, "nonLibrary")

    /** The configurations the Gradle law actually checks. Parsed, not retyped, so "main plane"
     *  means here exactly what it means in the build. */
    val lawChecked: Set<String> = namedStrings(stripped, "lawChecked")

    private companion object {
        /** The `mapOf( ... )` body of `val moduleLaw`, by brace depth — a regex for the whole
         *  literal would stop at the first `)` of `emptySet()`. */
        fun mainLawBody(text: String): String {
            val start = text.indexOf("val moduleLaw")
            require(start >= 0) { "splice.module-law.gradle.kts declares no `val moduleLaw`" }
            val open = text.indexOf('(', text.indexOf("mapOf", start))
            var depth = 0
            for (i in open until text.length) {
                when (text[i]) {
                    '(' -> depth++
                    ')' -> {
                        depth--
                        if (depth == 0) return text.substring(open + 1, i)
                    }
                }
            }
            error("splice.module-law.gradle.kts: `val moduleLaw`'s mapOf( is never closed")
        }

        /** Every `:module` path inside a `setOf(...)` body. */
        val MODULE_PATH_IN_SET = Regex(":[A-Za-z0-9._\\-]+")

        /** Every bare quoted token inside a `setOf(...)` body (configuration names). */
        val QUOTED_TOKEN = Regex("\"([A-Za-z0-9._\\-]+)\"")

        fun namedSet(text: String, name: String): Set<String> =
            MODULE_PATH_IN_SET.findAll(namedSetBody(text, name)).map { it.groupValues[0] }.toSet()

        fun namedStrings(text: String, name: String): Set<String> =
            QUOTED_TOKEN.findAll(namedSetBody(text, name)).map { it.groupValues[1] }.toSet()

        fun namedSetBody(text: String, name: String): String {
            val start = text.indexOf("val $name")
            require(start >= 0) { "splice.module-law.gradle.kts declares no `val $name`" }
            val open = text.indexOf('(', start)
            val close = text.indexOf(')', open)
            require(open in 0 until close) { "splice.module-law.gradle.kts: `val $name` is not a setOf(...)" }
            return text.substring(open + 1, close)
        }
    }
}

/** V4-91: drift between the Gradle main law and the Konsist test plane, as violation lines. PURE.
 *
 *  THREE PROPERTIES, each of which is a way the two maps could stop describing one architecture:
 *   · ACCOUNTED FOR — every module the Gradle law governs must appear in the test plane or be
 *     explicitly unrestricted. A module in neither is silently exempt on the test plane while the
 *     build still governs its main edges.
 *   · THE SAME HARNESS SET — Gradle's `nonLibrary` must equal UNRESTRICTED_MODULES. Konsist's own
 *     comment claims they are "the same set splice.module-law.gradle.kts calls nonLibrary", and an
 *     unchecked claim in a comment is how the copy drifted in the first place.
 *   · TEST ⊇ MAIN — a module's test-plane allowance must include everything its MAIN plane allows.
 *     A main dependency is on the test compile classpath by construction, so a test plane that
 *     forbids it describes a build that cannot exist, and the law would be unfalsifiable there. */
private fun lawDriftViolations(
    gradleMain: Map<String, Set<String>>,
    testPlane: Map<String, Set<String>>,
    gradleNonLibrary: Set<String>,
    unrestricted: Set<String>,
): List<String> {
    val violations = mutableListOf<String>()
    (gradleMain.keys - testPlane.keys - unrestricted).sorted().forEach { module ->
        violations += "$module is governed by the Gradle module law's main plane but appears in " +
            "neither MODULE_DEPENDENCY_LAW nor UNRESTRICTED_MODULES — its TEST edges are ungoverned."
    }
    (testPlane.keys - gradleMain.keys).sorted().forEach { module ->
        violations += "MODULE_DEPENDENCY_LAW governs $module, which the Gradle module law does not " +
            "mention — one of the two maps is stale; the build's map is the main plane's truth."
    }
    if (gradleNonLibrary != unrestricted) {
        violations += "the harness sets have drifted: splice.module-law.gradle.kts says nonLibrary=" +
            "${gradleNonLibrary.sorted()}, this file says UNRESTRICTED_MODULES=${unrestricted.sorted()}. " +
            "UNRESTRICTED_MODULES' own comment claims they are the same set."
    }
    (gradleMain.keys intersect testPlane.keys).sorted().forEach { module ->
        val missing = gradleMain.getValue(module) - testPlane.getValue(module)
        if (missing.isNotEmpty()) {
            violations += "$module: the Gradle main plane allows ${missing.sorted()} which " +
                "MODULE_DEPENDENCY_LAW does not — a main dependency is on the test compile " +
                "classpath by construction, so the test plane cannot be stricter than the main one."
        }
    }
    return violations
}

/** V4-91 (audit C row 4): a MAIN-plane allowance no build file declares, as violation lines. PURE.
 *
 *  Mirrors the stale-map arm the direction law already has for MODULE_DEPENDENCY_LAW keys ("names
 *  $module, which settings.gradle.kts does not include — it currently governs nothing"), one level
 *  down: an allowance nothing uses governs nothing either, and it reads as architecture that exists.
 *  Today's single instance is `:provider-muse -> :dialect-anthropic-passthrough`: provider-muse's
 *  build file declares :core, :provider-spi and a testFixtures edge, and MusePassthroughArm lives in
 *  :app — so the allowance describes a wire nobody has run, and a reader of the law believes muse
 *  speaks the passthrough dialect directly. */
private fun staleAllowanceViolations(
    gradleMain: Map<String, Set<String>>,
    mainEdges: Set<Pair<String, String>>,
): List<String> = gradleMain.entries.sortedBy { it.key }.flatMap { (module, allowed) ->
    (allowed - mainEdges.filter { it.first == module }.map { it.second }.toSet()).sorted().map { dep ->
        "$module is allowed to depend on $dep and no build file declares that edge — drop the " +
            "allowance, or make the edge real. An allowance nothing uses governs nothing, and it " +
            "reads as architecture that exists."
    }
}

/** RATCHET ALLOWLIST — the inverted edges that already existed when this law landed, every one of them
 *  test-configuration only. Each line is DEBT, not permission: the law passes today, every NEW
 *  inversion fails immediately, and this map is the visible worklist. Delete a line when the edge goes
 *  — a listed edge that no longer exists FAILS the law, so the list cannot rot into blanket permission. */
private val DEPENDENCY_RATCHET: Map<Pair<String, String>, String> = mapOf(
    (":provider-grok" to ":gateway") to
        "pre-existing, 2026-08-16, tracked for removal — grok's tests drive a real gateway server " +
        "(testImplementation + testFixtures); the harness belongs somewhere both can depend on.",
    (":provider-openai" to ":gateway") to
        "pre-existing, 2026-08-16, tracked for removal — same shape and same fix as provider-grok.",
    (":gateway" to ":provider-codex") to
        "pre-existing, 2026-08-16, tracked for removal — two :gateway tests still compile " +
        "against provider-codex (AccountTurnSelectionTest ChatGPT-Account-ID, " +
        "CodexCodeModeReanchorTest CodexCodeModeBridge); other gateway tests now use " +
        "TestResponsesProvider.",
)

class ArchitectureLawsTest {

    private val root: File = File(System.getProperty("gateway.root"))

    // Konsist resolves scopeFromDirectory RELATIVE to the Gradle root it detects;
    // absolute paths get prefixed and blow up (caught in this law's first red/green).
    private fun mainScope(module: String) =
        Konsist.scopeFromDirectory("$module/src/main/kotlin")

    // DR-165 (found by codex-splice's test audit, confirmed by a mutant that deleted a whole dialect
    // from PORT_SCOPE_MODULES while the suite stayed green): the denominator now comes from the
    // SOURCE TREE, and every module that ships production Kotlin must carry a DISPOSITION —
    // covered, or exempt with a written reason. Absence is not a disposition, so a module in
    // neither fails BY NAME instead of silently leaving coverage.
    @Test
    fun `slot headers - every production module is dispositioned, and its files declare PORT-OF or NEW`() {
        val onDisk = productionModules(root)
        org.junit.jupiter.api.Assertions.assertTrue(onDisk.size > 1) {
            "the source tree yielded ${onDisk.size} production modules — the walk is broken, " +
                "and a law that reads no modules passes vacuously."
        }
        val violations = slotHeaderDispositions(onDisk, PORT_SCOPE_MODULES.toSet(), SLOT_HEADER_EXEMPT)
        org.junit.jupiter.api.Assertions.assertTrue(violations.isEmpty()) {
            violations.joinToString(
                separator = "\n  - ",
                prefix = "SLOT-HEADER COVERAGE (DR-165) violated:\n  - ",
            )
        }
        (onDisk intersect PORT_SCOPE_MODULES.toSet()).forEach { module ->
            mainScope(module).files.assertTrue(additionalMessage = SLOT_HEADER_LAW) { file ->
                val firstLine = file.text.lineSequence().firstOrNull().orEmpty()
                firstLine.startsWith("// PORT-OF: ") || firstLine.startsWith("// NEW: ")
            }
        }
    }

    // DR-165: the disposition logic proven against SYNTHETIC input, because the live tree cannot
    // falsify it — with every module dispositioned, deleting the check entirely would still pass.
    // That is precisely the tautology this row exists to remove, so the guard gets its own fixture,
    // exactly as DR-112 did for the edge matcher. The boring cases are the ones that get waved
    // through, so they are all here: an unlisted module, a stale listing, and a blank reason.
    @Test
    fun `the disposition guard can actually fail - DR-165`() {
        assertEquals(
            emptyList<String>(),
            slotHeaderDispositions(setOf("core", "harness"), setOf("core"), mapOf("harness" to "a reason")),
            "covered plus exempt-with-a-reason is a complete disposition",
        )
        assertEquals(
            listOf(
                "newmod ships production Kotlin but is in neither PORT_SCOPE_MODULES nor " +
                    "SLOT_HEADER_EXEMPT — cover it (preferred) or exempt it WITH a written reason.",
            ),
            slotHeaderDispositions(setOf("core", "newmod"), setOf("core"), emptyMap()),
            "a module the source tree has and no list mentions must fail BY NAME",
        )
        assertEquals(
            listOf(
                "harness is exempted from the slot-header law with a blank reason — a placeholder " +
                    "is an absence wearing a label; write why, or cover the module.",
            ),
            slotHeaderDispositions(setOf("core", "harness"), setOf("core"), mapOf("harness" to "  ")),
            "a blank reason is not a disposition",
        )
        assertEquals(
            listOf(
                "PORT_SCOPE_MODULES names gone, which ships no production Kotlin — drop the entry; " +
                    "it currently governs nothing.",
            ),
            slotHeaderDispositions(setOf("core"), setOf("core", "gone"), emptyMap()),
            "a listing that governs nothing must fail, or the list rots into decoration",
        )
    }

    // DR-165: the CONSUMER half of the contract law, proven against synthetic input for the same
    // reason. Every live *RequestBuilder module already ships both a fixture and a *ContractTest, so
    // nothing in the tree can red it — and a guard the tree cannot falsify is the shape this row
    // exists to remove, not one it may quietly add.
    @Test
    fun `the contract-coverage guard can actually fail - DR-165`() {
        assertEquals(null, contractViolation("dialect-x", hasFixture = true, hasConsumer = true))
        assertEquals(
            "dialect-x ships a *RequestBuilder but no src/test/resources/contract/<name>.json",
            contractViolation("dialect-x", hasFixture = false, hasConsumer = true),
            "a builder with no fixture is the #924 Phase 1 case",
        )
        assertEquals(
            "dialect-x has a contract fixture but no *ContractTest.kt reading it — a golden " +
                "nothing compares against pins nothing",
            contractViolation("dialect-x", hasFixture = true, hasConsumer = false),
            "a fixture with no consumer is the fail-open one layer down",
        )
    }

    @Test
    fun `core stays framework-free - no ktor imports in core`() {
        val dir = File(root, "core/src/main/kotlin")
        if (!dir.exists()) return
        mainScope("core").imports.assertTrue { !it.name.startsWith("io.ktor") }
    }

    // V4-91 (audit A rows 3, 10, 11): the OS half of the same law — no child processes, no network.
    // See CORE_FORBIDDEN_IMPORT_PREFIXES for the three matchers, and for the dated reason
    // java.net.URI, the java.net exception types and java.nio.channels file locking are ALLOWED.
    // RED on this tree today by design, at exactly one locus: V4-103 routes HookScriptFiles'
    // ProcessBuilder through a port. The red inventory is recorded in the V4-91 ledger note.
    @Test
    fun `core reaches no OS escape - no child processes and no network in core main`() {
        val dir = File(root, "core/src/main/kotlin")
        org.junit.jupiter.api.Assertions.assertTrue(dir.isDirectory) {
            "core/src/main/kotlin is missing — a law that cannot read the module it governs must not " +
                "pass; fix the gateway.root system property or the module layout."
        }
        val files = dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        org.junit.jupiter.api.Assertions.assertTrue(files.size > 10) {
            "core ships ${files.size} production file(s) — the walk is broken, and a law that reads " +
                "no files passes vacuously."
        }
        val violations = files.sortedBy { it.path }.flatMap { file ->
            coreEscapeViolations(file.relativeTo(root).path, file.readText())
        }
        org.junit.jupiter.api.Assertions.assertTrue(violations.isEmpty()) {
            violations.joinToString(
                separator = "\n  - ",
                prefix = "CORE OS-ESCAPE LAW (V4-91, audit A rows 3/10/11) violated:\n  - ",
            )
        }
    }

    // V4-91: the escape matcher proven against SYNTHETIC input. The live tree reds it at one locus
    // today, but the day V4-103 lands a deleted matcher would look exactly like a clean :core —
    // which is the unfalsifiable shape DR-165 removed from the slot-header law. The BORING cases
    // are the ones that get waved through, so every deliberately-allowed spelling is pinned here
    // too: without them the narrowing is a claim in a comment rather than a checked property.
    @Test
    fun `the core escape guard can actually fail - V4-91`() {
        assertEquals(
            emptyList<String>(),
            coreEscapeViolations(
                "core/src/main/kotlin/splice/core/Ok.kt",
                "package splice.core\n" +
                    "import java.net.URI\n" +
                    "import java.nio.channels.FileChannel\n" +
                    "import java.nio.channels.FileLock\n" +
                    "import java.nio.file.Path\n" +
                    "/** A noexec mount surfaces here as ProcessBuilder's IOException. */\n" +
                    "internal class Ok(val base: URI, val path: Path) {\n" +
                    "    fun classify(e: Throwable) = when (e) {\n" +
                    "        is java.net.SocketException, is java.net.UnknownHostException -> \"net\"\n" +
                    "        else -> \"other\"\n" +
                    "    }\n" +
                    "}\n",
            ),
            "THE NARROWING, pinned: java.net.URI is a value type, java.nio.channels is the file " +
                "locking :core already owns through java.nio.file, and prose that names " +
                "ProcessBuilder without calling it is prose. The `when` branch is the regression " +
                "test for the substring hazard: `java.net.SocketException` CONTAINS " +
                "`java.net.Socket`, and an unbounded matcher named SafeFailureText.kt:18 as :core " +
                "dialling out.",
        )
        assertEquals(
            listOf(
                "core/X.kt:2 imports java.net.Socket — :core opens no network; declare a port in " +
                    ":core and implement it in :app (mirror FileIoTask/DirectoryProbe).",
            ),
            coreEscapeViolations("core/X.kt", "package splice.core\nimport java.net.Socket\n"),
            "a java.net type that dials must fail BY NAME with its line",
        )
        assertEquals(
            listOf(
                "core/H.kt:2 imports java.net.http.HttpClient — :core opens no network; declare a " +
                    "port in :core and implement it in :app (mirror FileIoTask/DirectoryProbe).",
            ),
            coreEscapeViolations("core/H.kt", "package splice.core\nimport java.net.http.HttpClient\n"),
            "java.net.http goes wholesale — every member of it is network I/O",
        )
        assertEquals(
            listOf(
                "core/F.kt:2 names java.net.URL fully qualified — :core opens no network, and " +
                    "skipping the import does not skip the law; declare a port in :core and " +
                    "implement it in :app (mirror FileIoTask/DirectoryProbe).",
            ),
            coreEscapeViolations("core/F.kt", "package splice.core\nval u = java.net.URL(\"http://x\")\n"),
            "the import-free bypass: a fully-qualified use is the same escape",
        )
        assertEquals(
            listOf(
                "core/Z.kt:2 uses ProcessBuilder — :core spawns no process; java.lang is imported " +
                    "implicitly, so no import denylist can see this. Declare a port in :core and " +
                    "implement it in :app.",
            ),
            coreEscapeViolations("core/Z.kt", "package splice.core\nval p = ProcessBuilder(\"x\").start()\n"),
            "THE ROW'S OWN LOCUS: ProcessBuilder is never imported, so only a reference matcher sees it",
        )
        assertEquals(
            listOf(
                "core/W.kt:2 uses Runtime.getRuntime — :core spawns no process; java.lang is " +
                    "imported implicitly, so no import denylist can see this. Declare a port in " +
                    ":core and implement it in :app.",
            ),
            coreEscapeViolations("core/W.kt", "package splice.core\nval r = Runtime.getRuntime().exec(\"x\")\n"),
            "the other implicit spelling of spawning a child",
        )
    }

    @Test
    fun `core wire types are serializable`() {
        val dir = File(root, "core/src/main/kotlin")
        if (!dir.exists()) return
        mainScope("core")
            .classes()
            .filter { it.resideInPackage("..wire..") }
            .assertTrue { cls -> cls.annotations.any { it.name.endsWith("Serializable") } }
    }

    // C3 coverage-by-law (#924 Phase 1): the request-byte contract is not opt-in. Every module that
    // ships a *RequestBuilder must also ship at least one contract/<name>.json golden — so a new
    // dialect arrives WITH its exact-request-bytes fixture (the stream_options / gzip incident class
    // becomes a failing unit test) rather than un-pinned. The receipt-binding half (a changed golden
    // must match a live-200 receipt) activates on traffic; see gateway/CONTRACT.md.
    // DR-165: the builder modules come from the SOURCE TREE, not from PORT_SCOPE_MODULES. Derived
    // from the allowlist, this law could be switched off for a whole dialect by deleting one string
    // — codex-splice's mutant removed dialect-openai-responses and the suite stayed green 16/16,
    // so that module's request bytes went unpinned while the law still reported coverage.
    //
    // It also now requires a live CONSUMER, not only a fixture: a contract/<name>.json that no
    // *ContractTest reads is a golden nothing compares against — the same fail-open one layer down,
    // and the reason a fixture alone was never the guarantee this law claims to give.
    @Test
    fun `every RequestBuilder module ships a request-byte contract fixture and a test that reads it`() {
        val builderModules = productionModules(root).filter { module ->
            val mainDir = File(root, "$module/src/main/kotlin")
            mainDir.isDirectory && mainDir.walkTopDown().any { it.isFile && it.name.endsWith("RequestBuilder.kt") }
        }
        org.junit.jupiter.api.Assertions.assertTrue(
            builderModules.isNotEmpty(),
            "expected at least one *RequestBuilder module — did the module layout change?",
        )
        val violations = builderModules.mapNotNull { module ->
            val contractDir = File(root, "$module/src/test/resources/contract")
            val hasFixture =
                contractDir.isDirectory && !contractDir.listFiles { f -> f.extension == "json" }.isNullOrEmpty()
            val testDir = File(root, "$module/src/test/kotlin")
            val hasConsumer = testDir.isDirectory &&
                testDir.walkTopDown().any { it.isFile && it.name.endsWith("ContractTest.kt") }
            contractViolation(module, hasFixture, hasConsumer)
        }
        org.junit.jupiter.api.Assertions.assertTrue(violations.isEmpty()) {
            violations.joinToString(
                separator = "\n  - ",
                prefix = "REQUEST-BYTE CONTRACT COVERAGE (#924 Phase 1, DR-165) violated:\n  - ",
                postfix = "\nSee gateway/CONTRACT.md.",
            )
        }
    }

    // HD-11: the dormant-rule repair, module-graph half. A rule set that nothing routes reports zero
    // findings forever (.rules/kotlin did, for a month) — and so does an architecture diagram that
    // lives only in a doc. This makes the direction executable, and its exceptions countable.
    //
    // V4-91: the MAIN plane is now graded against the BUILD's own map, parsed out of
    // build-logic/src/main/kotlin/splice.module-law.gradle.kts, rather than against the hand copy
    // MODULE_DEPENDENCY_LAW used to carry. The configurations that count as "main" are read from the
    // same file (`lawChecked`), so the split this test draws is the split the build enforces, and
    // MODULE_DEPENDENCY_LAW governs exactly what the build's plugin exempts: the test plane.
    @Test
    fun `module dependency direction - main plane from the build, test plane from this file`() {
        val modules = includedModules()
        org.junit.jupiter.api.Assertions.assertTrue(modules.size > 1) {
            "settings.gradle.kts yielded ${modules.size} modules — the include() parse is broken, " +
                "and a law that reads no modules passes vacuously."
        }
        val law = moduleLaw()
        org.junit.jupiter.api.Assertions.assertTrue(law.mainLaw.size > 5) {
            "the Gradle module law parsed ${law.mainLaw.size} entries — the parse is broken, and a " +
                "main plane graded against an empty map passes vacuously."
        }
        org.junit.jupiter.api.Assertions.assertTrue(law.lawChecked.isNotEmpty()) {
            "the Gradle module law declares no `lawChecked` configurations — without them every edge " +
                "would fall to the test plane and the main plane would grade nothing."
        }
        val violations = mutableListOf<String>()

        // Every included module is either governed on the test plane or explicitly unrestricted. A
        // module in neither is silently exempt, which is the same fail-open as an unrouted rule dir.
        (modules - MODULE_DEPENDENCY_LAW.keys - UNRESTRICTED_MODULES).forEach { module ->
            violations += "$module is in settings.gradle.kts but in neither MODULE_DEPENDENCY_LAW " +
                "nor UNRESTRICTED_MODULES — add it to the law (preferred) or justify it as a harness."
        }
        // ...and the law must not name modules that do not exist: a typo'd key enforces nothing.
        (MODULE_DEPENDENCY_LAW.keys - modules).forEach { module ->
            violations += "MODULE_DEPENDENCY_LAW names $module, which settings.gradle.kts does not " +
                "include — fix the key or drop it; it currently governs nothing."
        }

        val edges = modules.flatMap { module ->
            configuredEdges(module).map { (configuration, dep) -> Triple(module, configuration, dep) }
        }
        val mainEdges = edges.filter { it.second in law.lawChecked }.map { it.first to it.third }.toSet()
        val testEdges = edges.filterNot { it.second in law.lawChecked }.map { it.first to it.third }.toSet()

        mainEdges.sortedBy { it.first + it.second }.forEach { (module, dep) ->
            val allowed = law.mainLaw[module] ?: return@forEach
            if (dep in allowed) return@forEach
            violations += "$module may not depend on $dep in a MAIN configuration (the build's map " +
                "allows ${allowed.sorted()}). This is also a configuration-time build error; the " +
                "law repeats it so the failure names the edge. Change the map in " +
                "build-logic/src/main/kotlin/splice.module-law.gradle.kts if the architecture moved."
        }
        testEdges.sortedBy { it.first + it.second }.forEach { (module, dep) ->
            val allowed = MODULE_DEPENDENCY_LAW[module] ?: return@forEach
            if (dep in allowed) return@forEach
            if ((module to dep) in DEPENDENCY_RATCHET) return@forEach
            violations += "$module may not depend on $dep in a TEST configuration (allowed: " +
                "${allowed.sorted()}). The direction is the architecture. If the edge is deliberate " +
                "and temporary, add '\"$module\" to \"$dep\"' to DEPENDENCY_RATCHET in this file with " +
                "a dated reason; otherwise invert it (depend on the port, not the layer above)."
        }
        // A ratchet only ratchets if paying the debt is what removes the line.
        DEPENDENCY_RATCHET.keys.filterNot { it in mainEdges || it in testEdges }.forEach { edge ->
            violations += "DEPENDENCY_RATCHET still lists ${edge.first} -> ${edge.second}, which no " +
                "longer exists — delete the entry so the list keeps meaning 'known debt'."
        }

        org.junit.jupiter.api.Assertions.assertTrue(violations.isEmpty()) {
            violations.joinToString(
                separator = "\n  - ",
                prefix = "MODULE DEPENDENCY DIRECTION (HD-11, V4-91) violated:\n  - ",
            )
        }
    }

    // V4-91 (audit C row 4): the two maps must keep describing ONE architecture. See
    // lawDriftViolations for the three properties and why each is a way they could drift apart.
    @Test
    fun `the module law maps do not drift - V4-91`() {
        val law = moduleLaw()
        val violations = lawDriftViolations(
            gradleMain = law.mainLaw,
            testPlane = MODULE_DEPENDENCY_LAW,
            gradleNonLibrary = law.nonLibrary,
            unrestricted = UNRESTRICTED_MODULES,
        )
        org.junit.jupiter.api.Assertions.assertTrue(violations.isEmpty()) {
            violations.joinToString(
                separator = "\n  - ",
                prefix = "MODULE LAW DRIFT (V4-91) — the build's map and this file disagree:\n  - ",
            )
        }
    }

    // V4-91: the drift guard proven against SYNTHETIC input. The live maps line up today, so with
    // the check deleted this suite would still pass — the unfalsifiable shape DR-165 exists to
    // remove. The boring cases are the ones that get waved through, so all four are here.
    @Test
    fun `the drift guard can actually fail - V4-91`() {
        val harness = setOf(":app")
        assertEquals(
            emptyList<String>(),
            lawDriftViolations(
                gradleMain = mapOf(":core" to emptySet(), ":spi" to setOf(":core"), ":app" to emptySet()),
                testPlane = mapOf(":core" to emptySet(), ":spi" to setOf(":core", ":fixtures")),
                gradleNonLibrary = harness,
                unrestricted = harness,
            ),
            "matching maps, a harness set that agrees, and a test plane that is a SUPERSET of main",
        )
        assertEquals(
            listOf(
                ":spi is governed by the Gradle module law's main plane but appears in neither " +
                    "MODULE_DEPENDENCY_LAW nor UNRESTRICTED_MODULES — its TEST edges are ungoverned.",
            ),
            lawDriftViolations(
                gradleMain = mapOf(":core" to emptySet(), ":spi" to setOf(":core")),
                testPlane = mapOf(":core" to emptySet()),
                gradleNonLibrary = emptySet(),
                unrestricted = emptySet(),
            ),
            "a module the build governs and this file forgot must fail BY NAME",
        )
        assertEquals(
            listOf(
                "MODULE_DEPENDENCY_LAW governs :ghost, which the Gradle module law does not mention " +
                    "— one of the two maps is stale; the build's map is the main plane's truth.",
            ),
            lawDriftViolations(
                gradleMain = mapOf(":core" to emptySet()),
                testPlane = mapOf(":core" to emptySet(), ":ghost" to emptySet()),
                gradleNonLibrary = emptySet(),
                unrestricted = emptySet(),
            ),
            "a key only this file has governs nothing on the plane that matters",
        )
        assertEquals(
            listOf(
                "the harness sets have drifted: splice.module-law.gradle.kts says " +
                    "nonLibrary=[:app, :spikes], this file says UNRESTRICTED_MODULES=[:app]. " +
                    "UNRESTRICTED_MODULES' own comment claims they are the same set.",
            ),
            lawDriftViolations(
                gradleMain = mapOf(":core" to emptySet()),
                testPlane = mapOf(":core" to emptySet()),
                gradleNonLibrary = setOf(":app", ":spikes"),
                unrestricted = setOf(":app"),
            ),
            "the claim that the two harness sets are one set has to be checkable",
        )
        assertEquals(
            listOf(
                ":spi: the Gradle main plane allows [:core] which MODULE_DEPENDENCY_LAW does not — a " +
                    "main dependency is on the test compile classpath by construction, so the test " +
                    "plane cannot be stricter than the main one.",
            ),
            lawDriftViolations(
                gradleMain = mapOf(":spi" to setOf(":core")),
                testPlane = mapOf(":spi" to emptySet()),
                gradleNonLibrary = emptySet(),
                unrestricted = emptySet(),
            ),
            "a test plane stricter than main describes a build that cannot exist",
        )
    }

    // V4-91 (audit C row 4): a main-plane allowance no build file declares. RED on this tree today
    // by design — `:provider-muse -> :dialect-anthropic-passthrough` is the row's own example, and
    // the fix row either drops the allowance or makes the edge real. The wall's job is to name it.
    @Test
    fun `no main-plane allowance is stale - V4-91`() {
        val law = moduleLaw()
        val modules = includedModules()
        val mainEdges = modules.flatMap { module ->
            configuredEdges(module)
                .filter { it.first in law.lawChecked }
                .map { module to it.second }
        }.toSet()
        org.junit.jupiter.api.Assertions.assertTrue(mainEdges.size > 5) {
            "parsed ${mainEdges.size} main-configuration edges — the build-file walk is broken, and " +
                "every allowance would read as stale."
        }
        val violations = staleAllowanceViolations(law.mainLaw, mainEdges)
        org.junit.jupiter.api.Assertions.assertTrue(violations.isEmpty()) {
            violations.joinToString(
                separator = "\n  - ",
                prefix = "STALE MODULE-LAW ALLOWANCE (V4-91, audit C row 4):\n  - ",
            )
        }
    }

    // V4-91: the stale-allowance guard proven against SYNTHETIC input, so it keeps meaning something
    // after the fix row makes the live tree green. Mirrors the :324-style stale-map arm the direction
    // law already has for MODULE_DEPENDENCY_LAW keys, one level down.
    @Test
    fun `the stale-allowance guard can actually fail - V4-91`() {
        assertEquals(
            emptyList<String>(),
            staleAllowanceViolations(
                mapOf(":spi" to setOf(":core"), ":core" to emptySet()),
                setOf(":spi" to ":core"),
            ),
            "an allowance whose edge is declared is not stale, and an empty allowance cannot be",
        )
        assertEquals(
            listOf(
                ":spi is allowed to depend on :ghost and no build file declares that edge — drop the " +
                    "allowance, or make the edge real. An allowance nothing uses governs nothing, and " +
                    "it reads as architecture that exists.",
            ),
            staleAllowanceViolations(
                mapOf(":spi" to setOf(":core", ":ghost")),
                setOf(":spi" to ":core"),
            ),
            "an allowance nothing declares must fail BY NAME",
        )
        assertEquals(
            emptyList<String>(),
            staleAllowanceViolations(mapOf(":spi" to setOf(":core")), setOf(":spi" to ":core", ":other" to ":core")),
            "another module's edge to the same dep does not justify this module's allowance being kept",
        )
    }

    // V4-91: the configuration-aware edge matcher, pinned by fixture for the same reason DR-112
    // pinned the path-only one — the plane an edge lands on is now decided by its CONFIGURATION, so
    // an edge whose configuration the matcher misreads is graded by the wrong map entirely.
    @Test
    fun `every gradle spelling of a CONFIGURED project edge is seen - V4-91`() {
        val script = """
            dependencies {
                implementation(project(":core"))
                api(project(path = ":spi"))
                testImplementation(project( ":gateway" ))
                testImplementation(testFixtures(project(":gateway")))
                testFixturesImplementation(project(":core"))
                implementation(project(":app", configuration = "shadow"))
                implementation(project(  path  =  ":control"  ))
                // implementation(project(":commented-out"))
            }
        """.trimIndent()
        assertEquals(
            setOf(
                "implementation" to ":core",
                "api" to ":spi",
                // The plain and the testFixtures() spelling of the same edge collapse to one pair on
                // purpose: the plane is decided by the CONFIGURATION, and both are the test plane.
                "testImplementation" to ":gateway",
                "testFixturesImplementation" to ":core",
                "implementation" to ":app",
                "implementation" to ":control",
            ),
            configuredEdgesIn(script),
            "every Gradle spelling must be seen WITH the configuration that decides its plane",
        )
    }

    /** Module paths from settings.gradle.kts. Every quoted `:name` in that file is an include() entry —
     *  rootProject.name and includeBuild("build-logic") carry no leading colon. */

    // DR-112 (coverage redo, review 2026-08-31): the direction law and the ratchet-staleness check
    // both read edges through this matcher, so an edge written in any spelling it misses is simply
    // invisible to them — a silent hole, not a failure. No live edge uses the other forms, so this
    // fixture is the only thing that can fail when the matcher narrows.
    @Test
    fun `every gradle spelling of a project edge is seen - DR-112`() {
        val script = """
            dependencies {
                implementation(project(":core"))
                api(project(path = ":spi"))
                testImplementation(project( ":gateway" ))
                implementation(project(":app", configuration = "shadow"))
                implementation(project(  path  =  ":control"  ))
                // implementation(project(":commented-out"))
            }
        """.trimIndent()
        assertEquals(
            setOf(":core", ":spi", ":gateway", ":app", ":control"),
            projectEdgesIn(script),
            "every Gradle spelling of a project edge must be visible to the architecture laws",
        )
    }

    private fun includedModules(): Set<String> =
        MODULE_PATH.findAll(stripComments(File(root, "settings.gradle.kts").readText()))
            .map { it.groupValues[1] }
            .toSet()

    /** Project-dependency edges declared by a module's build file, in EVERY configuration —
     *  implementation, api, testImplementation, testFixtures(...) and the rest. */
    private fun projectDependencies(module: String): Set<String> {
        val buildFile = File(root, "${module.removePrefix(":")}/build.gradle.kts")
        if (!buildFile.isFile) return emptySet()
        return projectEdgesIn(buildFile.readText()).filterNot { it == module }.toSet()
    }

    /** V4-91: the BUILD's module law, parsed. Read per call rather than cached in a field: these
     *  tests are cheap, and a lazily-cached parse is a parse whose failure surfaces in whichever
     *  test happened to run first. */
    private fun moduleLaw(): ModuleLawFile =
        ModuleLawFile(File(root, "build-logic/src/main/kotlin/splice.module-law.gradle.kts").readText())

    /** V4-91: (configuration, project path) for every edge a module's build file declares. The
     *  configuration is what decides which PLANE the edge is graded on, so it travels with it. */
    private fun configuredEdges(module: String): Set<Pair<String, String>> {
        val buildFile = File(root, "${module.removePrefix(":")}/build.gradle.kts")
        if (!buildFile.isFile) return emptySet()
        return configuredEdgesIn(buildFile.readText()).filterNot { it.second == module }.toSet()
    }

    /** The pure half of [configuredEdges], pinned by a fixture for the DR-112 reason: an edge whose
     *  configuration the matcher misreads is graded against the wrong map, silently. */
    private fun configuredEdgesIn(script: String): Set<Pair<String, String>> =
        CONFIGURED_DEPENDENCY.findAll(stripComments(script))
            .map { it.groupValues[1] to it.groupValues[2] }
            .toSet()

    /** The pure half of [projectDependencies] — every edge a build script's TEXT declares, comments
     *  stripped. Split out (DR-112 coverage redo) so the SPELLINGS can be pinned by a synthetic
     *  fixture: the tree happens to write every live edge positionally, so the widened matcher was
     *  otherwise unfalsifiable, and the law it feeds would go quiet the day someone wrote one of
     *  the other forms. */
    private fun projectEdgesIn(script: String): Set<String> =
        PROJECT_DEPENDENCY.findAll(stripComments(script)).map { it.groupValues[1] }.toSet()

    /** Block and line comments out: a commented-out dependency is not an edge. */
    private fun stripComments(text: String): String =
        text.replace(BLOCK_COMMENT, "").replace(LINE_COMMENT, "")

    private companion object {
        val MODULE_PATH = Regex("\"(:[A-Za-z0-9._-]+)\"")

        // DR-112: match every Gradle spelling of a project edge — positional `project(":x")`, the
        // named-arg form `project(path = ":x")`, whitespace variants, and a trailing
        // `, configuration = ...` — not just the exact positional idiom. An edge written any other
        // way was invisible to the direction law and the ratchet-staleness check alike.
        val PROJECT_DEPENDENCY = Regex("""project\(\s*(?:path\s*=\s*)?"(:[A-Za-z0-9._-]+)"""")

        // V4-91: the same spellings, plus the CONFIGURATION that decides the edge's plane, plus the
        // `testFixtures(project(...))` wrapper the path-only matcher swallowed without noticing.
        val CONFIGURED_DEPENDENCY = Regex(
            """([A-Za-z][A-Za-z0-9]*)\s*\(\s*(?:testFixtures\s*\(\s*)?project\(\s*(?:path\s*=\s*)?"(:[A-Za-z0-9._-]+)"""",
        )
        val BLOCK_COMMENT = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)
        val LINE_COMMENT = Regex("//[^\n]*")

        const val SLOT_HEADER_LAW =
            "Slot-authoring law (#963): first line must be '// PORT-OF: <source> @ <sha> — invariants: ...' " +
                "for ported code or '// NEW: <reason>' for new code. The declaration is the survival artifact."
    }
}
