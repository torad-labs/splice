// NEW: Konsist module-law arms (V4-91), split out of ArchitectureLawsTest to clear detekt LargeClass.
package splice.quality

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** Every dialect module — what :daemon-head is allowed to know about, and what a provider picks from. */
private val DIALECTS = setOf(
    ":integrations-dialects-anthropic",
    ":integrations-dialects-openai-responses",
    ":integrations-dialects-openai-chat",
)

/** The domain plus the provider contract: what every adapter (dialect, provider, transport) starts from. */
private val ADAPTER_BASE = setOf(":core", ":integrations-upstream")

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
 *  :daemon-head tests reaching a DIALECT is legitimate and stays legal here, while a provider reaching
 *  back into :daemon-head is an inversion whether the wire is in main or in test. The entries therefore
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
    // the Claude Code side: isolated client state plus the concrete reader implementing the
    // sessions-owned transcript port. The sessions feature never points back into this adapter.
    ":integrations-claude-code" to setOf(":core", ":features-sessions"),
    ":integrations-mcp" to setOf(":core", ":integrations-claude-code", ":integrations-http"),
    ":integrations-http" to setOf(":core"),
    // the splice.toml file on disk: load, first-run starter, structural preflight and digest.
    ":integrations-topology" to setOf(":core"),
    // the operator-terminal toolkit (menus, spinner, raw mode, the wizard frame): the CLI's terminal
    // adapter, reading keys and drawing on the stream it is handed. Core only, so no daemon module can
    // reach a terminal through it.
    ":integrations-terminal" to setOf(":core"),
    // code mode's GraalJS worker pool: the child-JVM runtime behind the upstream-owned CodeModeRuntime port.
    ":integrations-codemode" to setOf(":core", ":integrations-upstream"),
    // the OAuth sign-in flows, account files and each vendor's refresh hop: the HTTP half of provider
    // auth, beside the HTTP-client-agnostic providers whose token shapes it speaks.
    ":integrations-oauth" to setOf(
        ":core", ":integrations-upstream", ":integrations-providers-codex", ":integrations-providers-grok",
        ":integrations-providers-kimi", ":integrations-providers-muse",
    ),
    // the provider contract. Speaks the domain and nothing else.
    ":integrations-upstream" to setOf(":core"),
    // a dialect adapts the contract to one wire format.
    ":integrations-dialects-anthropic" to ADAPTER_BASE,
    ":integrations-dialects-openai-responses" to ADAPTER_BASE,
    ":integrations-dialects-openai-chat" to ADAPTER_BASE,
    // a provider speaks its own dialect(s) — never another provider, never the transport.
    ":integrations-providers-codex" to ADAPTER_BASE + ":integrations-dialects-openai-responses",
    ":integrations-providers-grok" to ADAPTER_BASE + ":integrations-dialects-openai-responses",
    ":integrations-providers-kimi" to ADAPTER_BASE + ":integrations-dialects-anthropic",
    ":integrations-providers-muse" to ADAPTER_BASE,
    ":integrations-providers-openai" to ADAPTER_BASE + setOf(":integrations-dialects-openai-responses", ":integrations-dialects-openai-chat"),
    // the transport serves any dialect; it must not know a CONCRETE provider (that is :app's job).
    ":features-turns" to ADAPTER_BASE + DIALECTS + setOf(":integrations-http", ":features-sessions"),
    ":features-sessions" to setOf(":core", ":integrations-http"),
    ":features-models" to setOf(":core"),
    // Head lifecycle, logs, and status own their sequences and use the shared head contract.
    ":features-heads" to setOf(":core"),
    // sign-in, refresh, the account pool and their console routes; the pool is a read model every
    // operator surface renders.
    ":features-accounts" to setOf(":core", ":integrations-http"),
    ":features-usage" to setOf(":core", ":integrations-http", ":features-accounts"),
    // the daemon's own lifecycle: the draining restart and the upgrade surface.
    ":features-lifecycle" to emptySet(),
    // the doctor report and the one-prompt playground.
    ":features-diagnostics" to setOf(":core", ":integrations-http"),
    // launching Claude Code against a head: the exec recipe, the Claude head's wrap, the resume hook,
    // and the wrapper commands `splice install` links to the launch shim from splice.toml's heads.
    ":features-launch" to setOf(":core", ":integrations-claude-code", ":integrations-http", ":integrations-topology"),
    // the daemon's knobs and splice.toml, read and written as data.
    ":features-configuration" to setOf(":core", ":integrations-http"),
    // the console's live event stream: the bus, its event shapes, and GET /api/events.
    ":features-events" to setOf(":integrations-http"),
    // the management plane reads the domain, the client side it assembles a launch spec for, and
    // the head-start slice it delegates starting a head to.
    // the operator console: a Bun/Vite workspace with no Kotlin and no module edges (PR 4).
    ":console" to emptySet(),
)

/** Exempt from the direction law: :app is the composition root and may wire anything, and the rest are
 *  harnesses rather than product layers — the same set splice.module-law.gradle.kts calls `nonLibrary`. */
private val UNRESTRICTED_MODULES = setOf(":app", ":quality-architecture", ":quality-compiler-plugin")

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

/** The skip set of the FQN pass: import lines and every comment form. The original four-way
 *  startsWith chain (import, a line-comment open, an asterisk, and a block-comment open) tripped
 *  ComplexCondition, so the same four prefixes are one anchored regex. */
private val FQN_SKIP_LINE = Regex("""^import |^//|^\*|^/\*""")

/** V4-91: one file's :core escapes, as violation lines. PURE so it can be proven against synthetic
 *  input — the live tree can red it today, but the day the fix row lands, a silently-deleted
 *  matcher would look exactly like a clean tree. Same discipline as the DR-165 guards above. */
private fun coreEscapeViolations(path: String, text: String): List<String> =
    bannedImportViolations(path, text) +
        fullyQualifiedEscapeViolations(path, text) +
        forbiddenReferenceViolations(path, text)

/** First of the three escape matchers: a banned import, named or by java.net.http prefix. */
private fun bannedImportViolations(path: String, text: String): List<String> {
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
    return violations
}

/** Second of the three: the same types written fully qualified with no import. Comment lines are
 *  skipped so a sentence NAMING a banned type is prose, exactly as the reference matcher's `(` intends. */
private fun fullyQualifiedEscapeViolations(path: String, text: String): List<String> {
    val violations = mutableListOf<String>()
    val remedy = "declare a port in :core and implement it in :app (mirror FileIoTask/DirectoryProbe)."
    text.lineSequence().forEachIndexed { index, line ->
        val trimmed = line.trim()
        if (FQN_SKIP_LINE.containsMatchIn(trimmed)) return@forEachIndexed
        val hit = CORE_FORBIDDEN_FQN_USES.firstOrNull { it.second.containsMatchIn(line) }?.first
            ?: CORE_FORBIDDEN_IMPORT_PREFIXES.firstOrNull { line.contains(it) }
        if (hit != null) {
            violations += "$path:${index + 1} names $hit fully qualified — :core opens no network, " +
                "and skipping the import does not skip the law; $remedy"
        }
    }
    return violations
}

/** Third of the three: reference matching runs over the whole file rather than per line so a call
 *  split across lines still counts; the `(` in the needle is what keeps prose out. */
private fun forbiddenReferenceViolations(path: String, text: String): List<String> =
    CORE_FORBIDDEN_REFERENCES.filter { text.contains(it) }.map { reference ->
        val line = text.lineSequence().indexOfFirst { it.contains(reference) } + 1
        "$path:$line uses ${reference.removeSuffix("(")} — :core spawns no process; " +
            "java.lang is imported implicitly, so no import denylist can see this. Declare a port " +
            "in :core and implement it in :app."
    }

/** Synthetic fixture sources for `the core escape guard can actually fail` — the exact bytes the
 *  guard is proven against, hoisted out of the test method so the method stays under LongMethod. */

private const val CORE_ESCAPE_NARROWING_SOURCE = "package splice.core\n" +
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
    "}\n"

private const val CORE_ESCAPE_SOCKET_IMPORT_SOURCE = "package splice.core\nimport java.net.Socket\n"

private const val CORE_ESCAPE_HTTP_CLIENT_IMPORT_SOURCE = "package splice.core\nimport java.net.http.HttpClient\n"

private const val CORE_ESCAPE_FQN_URL_SOURCE = "package splice.core\nval u = java.net.URL(\"http://x\")\n"

private const val CORE_ESCAPE_PROCESS_BUILDER_SOURCE = "package splice.core\nval p = ProcessBuilder(\"x\").start()\n"

private const val CORE_ESCAPE_RUNTIME_EXEC_SOURCE = "package splice.core\nval r = Runtime.getRuntime().exec(\"x\")\n"

private const val CORE_ESCAPE_SOCKET_EXPECTED = "core/X.kt:2 imports java.net.Socket — :core opens no network; declare a port in :core and implement it in :app (mirror FileIoTask/DirectoryProbe)."

private const val CORE_ESCAPE_HTTP_CLIENT_EXPECTED = "core/H.kt:2 imports java.net.http.HttpClient — :core opens no network; declare a port in :core and implement it in :app (mirror FileIoTask/DirectoryProbe)."

private const val CORE_ESCAPE_FQN_URL_EXPECTED = "core/F.kt:2 names java.net.URL fully qualified — :core opens no network, and skipping the import does not skip the law; declare a port in :core and implement it in :app (mirror FileIoTask/DirectoryProbe)."

private const val CORE_ESCAPE_PROCESS_BUILDER_EXPECTED = "core/Z.kt:2 uses ProcessBuilder — :core spawns no process; java.lang is imported implicitly, so no import denylist can see this. Declare a port in :core and implement it in :app."

private const val CORE_ESCAPE_RUNTIME_EXEC_EXPECTED = "core/W.kt:2 uses Runtime.getRuntime — :core spawns no process; java.lang is imported implicitly, so no import denylist can see this. Declare a port in :core and implement it in :app."

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

/** P3 (restructure §1.3): a module's Gradle id is its directory with `/` → `-`, stated once in
 *  settings.gradle.kts and read here from the build's own map — a module that moves keeps no old id.
 *  The pending set that let modules move one commit at a time left with the last move (PR 3). */
private fun idDerivationViolations(map: ProjectMap): List<String> {
    val violations = mutableListOf<String>()
    map.modules.sorted().forEach { module ->
        val directory = map.relativeDir(module)
        val derived = ":" + directory.replace('/', '-')
        if (derived != module) {
            violations += "$module lives at $directory but its id is not $derived — the id is derived " +
                "from the directory (`/` → `-`), stated once in settings.gradle.kts."
        }
    }
    return violations
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
 *  $module, which the project map does not include — it currently governs nothing"), one level
 *  down: an allowance nothing uses governs nothing either, and it reads as architecture that exists.
 *  The one live instance, `:providers-muse -> :dialects-anthropic` (:providers-muse's build
 *  file declares only :core/:upstream and MusePassthroughArm lives in :app — the allowance
 *  described a wire nobody ran), was dropped in V4-103: MusePassthroughArm stays in :app, so the
 *  provider module keeps only the :core/:upstream edges it actually declares. */
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
    (":integrations-providers-grok" to ":features-turns") to
        "pre-existing, 2026-08-16, tracked for removal — grok's tests drive a real gateway server " +
        "(testImplementation + testFixtures); the harness belongs somewhere both can depend on.",
    (":integrations-providers-openai" to ":features-turns") to
        "pre-existing, 2026-08-16, tracked for removal — same shape and same fix as :providers-grok.",
    (":features-turns" to ":integrations-providers-codex") to
        "pre-existing, 2026-08-16, tracked for removal — two turn-serving tests still compile " +
        "against :providers-codex (AccountTurnSelectionTest ChatGPT-Account-ID, " +
        "CodexCodeModeReanchorTest CodexCodeModeBridge); other gateway tests now use " +
        "TestResponsesProvider.",
)

/** Synthetic drift-law inputs for `the drift guard can actually fail`, hoisted out of the test
 *  method for the same LongMethod reason as the core-escape fixtures above. Each returns the exact
 *  violation list the test asserts. */

private fun driftMatchingMaps(): List<String> = lawDriftViolations(
    gradleMain = mapOf(":core" to emptySet(), ":spi" to setOf(":core"), ":app" to emptySet()),
    testPlane = mapOf(":core" to emptySet(), ":spi" to setOf(":core", ":fixtures")),
    gradleNonLibrary = setOf(":app"),
    unrestricted = setOf(":app"),
)

private fun driftForgottenModule(): List<String> = lawDriftViolations(
    gradleMain = mapOf(":core" to emptySet(), ":spi" to setOf(":core")),
    testPlane = mapOf(":core" to emptySet()),
    gradleNonLibrary = emptySet(),
    unrestricted = emptySet(),
)

private fun driftGhostKey(): List<String> = lawDriftViolations(
    gradleMain = mapOf(":core" to emptySet()),
    testPlane = mapOf(":core" to emptySet(), ":ghost" to emptySet()),
    gradleNonLibrary = emptySet(),
    unrestricted = emptySet(),
)

private fun driftHarnessSets(): List<String> = lawDriftViolations(
    gradleMain = mapOf(":core" to emptySet()),
    testPlane = mapOf(":core" to emptySet()),
    gradleNonLibrary = setOf(":app", ":spikes"),
    unrestricted = setOf(":app"),
)

private fun driftStricterTestPlane(): List<String> = lawDriftViolations(
    gradleMain = mapOf(":spi" to setOf(":core")),
    testPlane = mapOf(":spi" to emptySet()),
    gradleNonLibrary = emptySet(),
    unrestricted = emptySet(),
)

private const val DRIFT_FORGOTTEN_EXPECTED = ":spi is governed by the Gradle module law's main plane but appears in neither MODULE_DEPENDENCY_LAW nor UNRESTRICTED_MODULES — its TEST edges are ungoverned."

private const val DRIFT_GHOST_EXPECTED = "MODULE_DEPENDENCY_LAW governs :ghost, which the Gradle module law does not mention — one of the two maps is stale; the build's map is the main plane's truth."

private const val DRIFT_HARNESS_EXPECTED = "the harness sets have drifted: splice.module-law.gradle.kts says nonLibrary=[:app, :spikes], this file says UNRESTRICTED_MODULES=[:app]. UNRESTRICTED_MODULES' own comment claims they are the same set."

private const val DRIFT_STRICTER_EXPECTED = ":spi: the Gradle main plane allows [:core] which MODULE_DEPENDENCY_LAW does not — a main dependency is on the test compile classpath by construction, so the test plane cannot be stricter than the main one."

/** P0: the synthetic build files the nested-module proof grades. Small enough to read, and
 *  independent of the live build's map — a proof that borrowed the real law would move with it. */

private val NESTED_MODULE_LAW_SOURCE = """
    val moduleLaw: Map<String, Set<String>> = mapOf(
        ":provider-x" to setOf(":core"),
    )
    val nonLibrary = setOf(":app")
    val lawChecked = setOf("api", "implementation")
""".trimIndent()

private val NESTED_MODULE_BUILD_FILE = """
    dependencies {
        implementation(project(":daemon-head"))
    }
""".trimIndent()

private const val NESTED_EDGE_EXPECTED = ":provider-x may not depend on :daemon-head in a MAIN configuration (the build's map allows [:core]). This is also a configuration-time build error; the law repeats it so the failure names the edge. Change the map in build-logic/src/main/kotlin/splice.module-law.gradle.kts if the architecture moved."

class ModuleLawsTest {

    // P0: the module set and every module's directory come from the BUILD, through one channel
    // that fails by name when it is absent — see ProjectMap.kt. This replaces both the
    // settings.gradle.kts regex and the `root/<id>` path arithmetic below it.
    private val map = ProjectMap.fromSystemProperties()

    // V4-91 (audit A rows 3, 10, 11): the OS half of the same law — no child processes, no network.
    // See CORE_FORBIDDEN_IMPORT_PREFIXES for the three matchers, and for the dated reason
    // java.net.URI, the java.net exception types and java.nio.channels file locking are ALLOWED.
    // RED on this tree today by design, at exactly one locus: V4-103 routes HookScriptFiles'
    // ProcessBuilder through a port. The red inventory is recorded in the V4-91 ledger note.
    @Test
    fun `core reaches no OS escape - no child processes and no network in core main`() {
        val dir = map.mainSources(":core")
        org.junit.jupiter.api.Assertions.assertTrue(dir.isDirectory) {
            "${map.relativeDir(":core")}/src/main/kotlin is missing — a law that cannot read the " +
                "module it governs must not pass; fix the project map (ProjectMap.kt) or the " +
                "module layout."
        }
        val files = dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        org.junit.jupiter.api.Assertions.assertTrue(files.size > 10) {
            "core ships ${files.size} production file(s) — the walk is broken, and a law that reads " +
                "no files passes vacuously."
        }
        val violations = files.sortedBy { it.path }.flatMap { file ->
            coreEscapeViolations(file.relativeTo(map.root).path, file.readText())
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
            coreEscapeViolations("core/src/main/kotlin/splice/core/Ok.kt", CORE_ESCAPE_NARROWING_SOURCE),
            "THE NARROWING, pinned: java.net.URI is a value type, java.nio.channels is the file " +
                "locking :core already owns through java.nio.file, and prose that names " +
                "ProcessBuilder without calling it is prose. The `when` branch is the regression " +
                "test for the substring hazard: `java.net.SocketException` CONTAINS " +
                "`java.net.Socket`, and an unbounded matcher named SafeFailureText.kt:18 as :core " +
                "dialling out.",
        )
        assertEquals(
            listOf(CORE_ESCAPE_SOCKET_EXPECTED),
            coreEscapeViolations("core/X.kt", CORE_ESCAPE_SOCKET_IMPORT_SOURCE),
            "a java.net type that dials must fail BY NAME with its line",
        )
        assertEquals(
            listOf(CORE_ESCAPE_HTTP_CLIENT_EXPECTED),
            coreEscapeViolations("core/H.kt", CORE_ESCAPE_HTTP_CLIENT_IMPORT_SOURCE),
            "java.net.http goes wholesale — every member of it is network I/O",
        )
        assertEquals(
            listOf(CORE_ESCAPE_FQN_URL_EXPECTED),
            coreEscapeViolations("core/F.kt", CORE_ESCAPE_FQN_URL_SOURCE),
            "the import-free bypass: a fully-qualified use is the same escape",
        )
        assertEquals(
            listOf(CORE_ESCAPE_PROCESS_BUILDER_EXPECTED),
            coreEscapeViolations("core/Z.kt", CORE_ESCAPE_PROCESS_BUILDER_SOURCE),
            "THE ROW'S OWN LOCUS: ProcessBuilder is never imported, so only a reference matcher sees it",
        )
        assertEquals(
            listOf(CORE_ESCAPE_RUNTIME_EXEC_EXPECTED),
            coreEscapeViolations("core/W.kt", CORE_ESCAPE_RUNTIME_EXEC_SOURCE),
            "the other implicit spelling of spawning a child",
        )
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
        val modules = map.modules
        org.junit.jupiter.api.Assertions.assertTrue(modules.size > 1) {
            "the project map yielded ${modules.size} module(s) — the channel is broken, and a law " +
                "that reads no modules passes vacuously."
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
        val violations = moduleDirectionViolations(
            map = map,
            law = law,
            testPlane = MODULE_DEPENDENCY_LAW,
            unrestricted = UNRESTRICTED_MODULES,
            ratchet = DEPENDENCY_RATCHET.keys,
        )
        org.junit.jupiter.api.Assertions.assertTrue(violations.isEmpty()) {
            violations.joinToString(
                separator = "\n  - ",
                prefix = "MODULE DEPENDENCY DIRECTION (HD-11, V4-91) violated:\n  - ",
            )
        }
    }

    // P0 (restructure §6.1) — THE RED PROOF for the project map, on the law that matters most: the
    // forbidden edge lives in a module whose directory is NESTED (providers/x), the case both old
    // readings lost. ArchitectureLawsTest.kt:35-43 listed only the root's immediate children, and
    // this file's resolver composed root/provider-x/build.gradle.kts and `return emptySet()` on
    // the miss — so against the OLD logic this fixture yields NO edges and reports compliance.
    @Test
    fun `a NESTED module's forbidden edge is graded and named - P0`(@TempDir temp: File) {
        val buildFile = File(temp, "providers/x/build.gradle.kts")
        check(buildFile.parentFile.mkdirs()) { "the fixture module directory was not created" }
        buildFile.writeText(NESTED_MODULE_BUILD_FILE)
        assertEquals(
            listOf(NESTED_EDGE_EXPECTED),
            moduleDirectionViolations(
                map = ProjectMap.parse(temp, ":provider-x=providers/x", fixtureNotSwept),
                law = ModuleLawFile(NESTED_MODULE_LAW_SOURCE),
                testPlane = mapOf(":provider-x" to setOf(":core")),
                unrestricted = emptySet(),
                ratchet = emptySet(),
            ),
            "a module under providers/ must be graded exactly like a flat one — its build file is " +
                "found through the map, never by composing root/<id>",
        )
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
        assertEquals(
            emptyList<String>(),
            driftMatchingMaps(),
            "matching maps, a harness set that agrees, and a test plane that is a SUPERSET of main",
        )
        assertEquals(
            listOf(DRIFT_FORGOTTEN_EXPECTED),
            driftForgottenModule(),
            "a module the build governs and this file forgot must fail BY NAME",
        )
        assertEquals(
            listOf(DRIFT_GHOST_EXPECTED),
            driftGhostKey(),
            "a key only this file has governs nothing on the plane that matters",
        )
        assertEquals(
            listOf(DRIFT_HARNESS_EXPECTED),
            driftHarnessSets(),
            "the claim that the two harness sets are one set has to be checkable",
        )
        assertEquals(
            listOf(DRIFT_STRICTER_EXPECTED),
            driftStricterTestPlane(),
            "a test plane stricter than main describes a build that cannot exist",
        )
    }

    // V4-91 (audit C row 4): a main-plane allowance no build file declares. RED on this tree today
    // by design — `:providers-muse -> :dialects-anthropic` is the row's own example, and
    // the fix row either drops the allowance or makes the edge real. The wall's job is to name it.
    // P3 (restructure §1.3): id ↔ directory, from the build's map (see idDerivationViolations).
    @Test
    fun `every module id derives from its directory - P3`() {
        assertEquals(
            emptyList<String>(),
            idDerivationViolations(map),
            "a module's id is its directory with `/` → `-`; a module that moved keeps no old id",
        )
    }

    @Test
    fun `the id-derivation law can actually fail - P3`() {
        assertEquals(
            listOf(
                ":gateway lives at daemon/head but its id is not :daemon-head — the id is derived from " +
                    "the directory (`/` → `-`), stated once in settings.gradle.kts.",
            ),
            idDerivationViolations(ProjectMap.parse(File("."), ":gateway=daemon/head", fixtureNotSwept)),
            "a moved module whose id was left behind must fail BY NAME",
        )
    }

    @Test
    fun `no main-plane allowance is stale - V4-91`() {
        val law = moduleLaw()
        val mainEdges = map.modules.flatMap { module ->
            configuredEdges(map, module)
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
                testImplementation(project( ":daemon-head" ))
                testImplementation(testFixtures(project(":daemon-head")))
                testFixturesImplementation(project(":core"))
                implementation(project(":app", configuration = "shadow"))
                implementation(project(  path  =  ":daemon-control"  ))
                // implementation(project(":commented-out"))
            }
        """.trimIndent()
        assertEquals(
            setOf(
                "implementation" to ":core",
                "api" to ":spi",
                // The plain and the testFixtures() spelling of the same edge collapse to one pair on
                // purpose: the plane is decided by the CONFIGURATION, and both are the test plane.
                "testImplementation" to ":daemon-head",
                "testFixturesImplementation" to ":core",
                "implementation" to ":app",
                "implementation" to ":daemon-control",
            ),
            configuredEdgesIn(script),
            "every Gradle spelling must be seen WITH the configuration that decides its plane",
        )
    }

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
                testImplementation(project( ":daemon-head" ))
                implementation(project(":app", configuration = "shadow"))
                implementation(project(  path  =  ":daemon-control"  ))
                // implementation(project(":commented-out"))
            }
        """.trimIndent()
        assertEquals(
            setOf(":core", ":spi", ":daemon-head", ":app", ":daemon-control"),
            projectEdgesIn(script),
            "every Gradle spelling of a project edge must be visible to the architecture laws",
        )
    }

    /** V4-91: the BUILD's module law, parsed. Read per call rather than cached in a field: these
     *  tests are cheap, and a lazily-cached parse is a parse whose failure surfaces in whichever
     *  test happened to run first. build-logic is an included BUILD, not a subproject, so it is
     *  the one path here the project map does not carry. */
    private fun moduleLaw(): ModuleLawFile =
        ModuleLawFile(File(map.root, "build-logic/src/main/kotlin/splice.module-law.gradle.kts").readText())

    /** V4-91: (configuration, project path) for every edge a module's build file declares. The
     *  configuration is what decides which PLANE the edge is graded on, so it travels with it.
     *
     *  P0: the build file is resolved THROUGH THE PROJECT MAP and a missing one is a hard failure
     *  ([ProjectMap.buildFile]). This resolver used to compose `root/<id>/build.gradle.kts` and
     *  `return emptySet()` when that file did not exist — so a module in a subdirectory, or one
     *  whose build file was deleted, kept every law green while being graded on no edges at all. */
    private fun configuredEdges(map: ProjectMap, module: String): Set<Pair<String, String>> =
        configuredEdgesIn(map.buildFile(module).readText()).filterNot { it.second == module }.toSet()

    /** The pure half of [configuredEdges], pinned by a fixture for the DR-112 reason: an edge whose
     *  configuration the matcher misreads is graded against the wrong map, silently. */
    private fun configuredEdgesIn(script: String): Set<Pair<String, String>> =
        CONFIGURED_DEPENDENCY.findAll(stripComments(script))
            .map { it.groupValues[1] to it.groupValues[2] }
            .toSet()

    /** The pure half of [configuredEdges]'s path-only matcher — every edge a build script's TEXT
     *  declares, comments stripped. Split out (DR-112 coverage redo) so the SPELLINGS can be pinned
     *  by a synthetic fixture: the tree happens to write every live edge positionally, so the
     *  widened matcher was otherwise unfalsifiable, and the law it feeds would go quiet the day
     *  someone wrote one of the other forms. */
    private fun projectEdgesIn(script: String): Set<String> =
        PROJECT_DEPENDENCY.findAll(stripComments(script)).map { it.groupValues[1] }.toSet()

    /** Block and line comments out: a commented-out dependency is not an edge. */
    private fun stripComments(text: String): String =
        text.replace(BLOCK_COMMENT, "").replace(LINE_COMMENT, "")

    /** V4-91: every module-direction violation, in the order the original test accumulated them —
     *  unaccounted modules and stale law keys first, then main-plane, test-plane and ratchet edges.
     *
     *  P0: the modules and their build files come from [map], and the test-plane maps are
     *  PARAMETERS rather than reads of this file's constants, so the whole law can be run against a
     *  synthetic tree — which is what proves a NESTED module is graded at all. */
    private fun moduleDirectionViolations(
        map: ProjectMap,
        law: ModuleLawFile,
        testPlane: Map<String, Set<String>>,
        unrestricted: Set<String>,
        ratchet: Set<Pair<String, String>>,
    ): List<String> {
        val violations = unaccountedModuleViolations(map.modules, testPlane, unrestricted)
        val edges = map.modules.flatMap { module ->
            configuredEdges(map, module).map { (configuration, dep) -> Triple(module, configuration, dep) }
        }
        val mainEdges = edges.filter { it.second in law.lawChecked }.map { it.first to it.third }.toSet()
        val testEdges = edges.filterNot { it.second in law.lawChecked }.map { it.first to it.third }.toSet()
        return violations +
            mainPlaneEdgeViolations(law, mainEdges) +
            testPlaneEdgeViolations(testEdges, testPlane, ratchet) +
            staleRatchetViolations(mainEdges, testEdges, ratchet)
    }

    private fun unaccountedModuleViolations(
        modules: Set<String>,
        testPlane: Map<String, Set<String>>,
        unrestricted: Set<String>,
    ): List<String> {
        val violations = mutableListOf<String>()
        (modules - testPlane.keys - unrestricted).forEach { module ->
            violations += "$module is in the project map but in neither MODULE_DEPENDENCY_LAW " +
                "nor UNRESTRICTED_MODULES — add it to the law (preferred) or justify it as a harness."
        }
        (testPlane.keys - modules).forEach { module ->
            violations += "MODULE_DEPENDENCY_LAW names $module, which the project map does not " +
                "include — fix the key or drop it; it currently governs nothing."
        }
        return violations
    }

    private fun mainPlaneEdgeViolations(
        law: ModuleLawFile,
        mainEdges: Set<Pair<String, String>>,
    ): List<String> = mainEdges.sortedBy { it.first + it.second }.mapNotNull { (module, dep) ->
        val allowed = law.mainLaw[module] ?: return@mapNotNull null
        if (dep in allowed) return@mapNotNull null
        "$module may not depend on $dep in a MAIN configuration (the build's map " +
            "allows ${allowed.sorted()}). This is also a configuration-time build error; the " +
            "law repeats it so the failure names the edge. Change the map in " +
            "build-logic/src/main/kotlin/splice.module-law.gradle.kts if the architecture moved."
    }

    private fun testPlaneEdgeViolations(
        testEdges: Set<Pair<String, String>>,
        testPlane: Map<String, Set<String>>,
        ratchet: Set<Pair<String, String>>,
    ): List<String> =
        testEdges.sortedBy { it.first + it.second }.mapNotNull { (module, dep) ->
            val allowed = testPlane[module] ?: return@mapNotNull null
            if (dep in allowed) return@mapNotNull null
            if ((module to dep) in ratchet) return@mapNotNull null
            "$module may not depend on $dep in a TEST configuration (allowed: " +
                "${allowed.sorted()}). The direction is the architecture. If the edge is deliberate " +
                "and temporary, add '\"$module\" to \"$dep\"' to DEPENDENCY_RATCHET in this file with " +
                "a dated reason; otherwise invert it (depend on the port, not the layer above)."
        }

    private fun staleRatchetViolations(
        mainEdges: Set<Pair<String, String>>,
        testEdges: Set<Pair<String, String>>,
        ratchet: Set<Pair<String, String>>,
    ): List<String> =
        ratchet.filterNot { it in mainEdges || it in testEdges }.map { edge ->
            "DEPENDENCY_RATCHET still lists ${edge.first} -> ${edge.second}, which no longer exists — " +
                "delete the entry so the list keeps meaning 'known debt'."
        }

    private companion object {
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
    }
}
