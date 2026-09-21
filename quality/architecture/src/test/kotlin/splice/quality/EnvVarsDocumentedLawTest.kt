// NEW: V4-87 — every environment variable splice READS has a disposition in the documentation
// (ported from checks/config/env-vars-documented.ts, restructure PR 6).
//
// WHY THIS EXISTS. An env var is the highest-precedence configuration layer splice has: it beats
// state config.json, it beats the TOML, it beats the knob default. It is also the only layer with
// no schema, no `splice doctor` line and no file an operator can diff — so an undocumented one is a
// setting that silently wins and that nobody can discover. A var read tomorrow is in scope with no
// edit to this file.
//
// THE SEAM. kt-no-system-getenv forces every environment read through ONE port,
// `splice.core.util.EnvReader`, threaded by constructor injection under exactly two spellings in
// main sources, `env` and `envReader`. That port is what makes this law possible at all: without it
// the denominator would be "every string anywhere", which is not a denominator.
//
// DENOMINATOR, from the SOURCE, never a hand list. Three resolution kinds, all read off disk:
//   literal — a string literal at a seam call site: `env("PATH")`, `envReader("SPLICE_CONFIG")`,
//             either with an explicit `.invoke(...)`;
//   const   — a file-local `const val NAME = "LITERAL"` passed to the seam, resolved to its literal;
//   knob    — every name in a `Knob` entry's `envNames` list in Knob.kt, read through the same seam
//             at ConfigService's `knob.envNames.firstNotNullOfOrNull { name -> envReader(name) }`.
// FOUR GUARDS refuse a vacuous pass: a direct `System.getenv("X")` CALL anywhere in main sources is
// red by file:line (a read this scan CANNOT see means the denominator is incomplete); zero seam
// call sites is a failure; zero names in Knob.kt's envNames is a failure; zero names overall is a
// failure rather than a pass.
//
// COMPUTED ARGUMENTS are excluded WITH A REASON: the name they read is not splice's to document
// (an operator-authored `env = "..."`, a per-head `HEADKEY_API_KEY` family). A computed site yields
// no name and a law cannot demand the documentation of a string that does not exist.
//
// DISPOSITION: documented — the name appears inside the ENVIRONMENT VARIABLES header block of
// app/src/main/resources/splice.example.toml (the sentinel line plus the contiguous run of comment and blank lines
// after it, which must itself state the precedence chain `env > TOML > default`) — or retired
// (`# retired: <NAME> — <reason>`, reason non-empty). Matching is CASE-SENSITIVE: `no_proxy` and
// `NO_PROXY` are two variables and the gateway reads both. NOT CAUGHT: a var splice WRITES
// (LaunchService's CLAUDE_CODE_* values are outbound), a var read outside the JVM, a seam-shaped
// identifier that is not the seam, and whether the documented prose is CORRECT.
package splice.quality

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

internal object EnvVarsDocumented {
    const val KNOB_IN_CORE = "splice/core/config/Knob.kt"
    const val EXAMPLE_CONFIG = "app/src/main/resources/splice.example.toml"

    // The header block that documents the environment layer. The sentinel tolerates the file's own
    // box-drawing decoration but nothing that carries meaning.
    private val BLOCK_SENTINEL = Regex(
        "^[ \\t]*#[ \\t#─━═=—–*_.-]*ENVIRONMENT VARIABLES\\b",
        RegexOption.IGNORE_CASE,
    )
    val PRECEDENCE_CHAIN = Regex("env[ \\t]*>[ \\t]*TOML[ \\t]*>[ \\t]*default", RegexOption.IGNORE_CASE)

    // The seam's two spellings in main sources, called or `.invoke`d.
    private val SEAM_CALL = Regex("(?<![\\w.])(env|envReader)[ \\t]*(?:\\.invoke[ \\t]*)?\\(")

    // A direct read the seam scan cannot see. `System::getenv` (a reference handed to an EnvReader)
    // is injection and deliberately NOT matched.
    private val DIRECT_GETENV = Regex("(?<![\\w:])(?:(?:java\\.lang\\.)?System\\s*\\.\\s*)?getenv[ \\t]*\\(")
    private val CONST_DECL = Regex("\\bconst\\s+val\\s+([A-Za-z_]\\w*)\\s*(?::[^=]+)?=\\s*\"([^\"]*)\"")
    private val STRING_ARG = Regex("^\\s*\"([^\"]*)\"\\s*$")
    private val IDENT_ARG = Regex("^\\s*([A-Za-z_]\\w*)\\s*$")
    private val ENUM_DECL = Regex("\\benum\\s+class\\s+Knob\\b")
    private val ENTRY_HEAD = Regex("^\\s*([A-Z][A-Z0-9_]*)\\s*\\(", RegexOption.DOT_MATCHES_ALL)

    /** One env var name and the first place it is read. */
    data class EnvName(val name: String, val kind: String, val where: String)

    data class Denominator(val names: Map<String, EnvName>, val computed: List<String>, val problems: List<String>)

    /** A disposition surface: the path a violation names, and the file. */
    data class Surface(val rel: String, val file: File)

    /** What the seam scan accumulates over every file. */
    private class Scan {
        val names = mutableListOf<EnvName>()
        val computed = mutableListOf<String>()
        val problems = mutableListOf<String>()
        var sites = 0
    }

    /** (names, computed sites, problems) from the EnvReader seam in [files], named relative to [root]. */
    fun scanSeam(files: List<File>, root: File): Denominator {
        val scan = Scan()
        for (file in files) {
            scanFile(file.relativeTo(root).invariantSeparatorsPath, KotlinText.blankComments(file.readText()), scan)
        }
        if (scan.sites == 0) {
            scan.problems += "no EnvReader seam call site found in main sources — the scan has lost the seam, and a " +
                "gateway that reads no environment is not the gateway this wall was written against"
        }
        return Denominator(scan.names.associateByFirst(), scan.computed, scan.problems)
    }

    /** A literal can itself contain something that looks like a seam call (McpSharing's "malformed
     *  env (expected string values)"), so matches starting inside a string literal are dropped —
     *  the string-only reading of [KotlinText.kinds], over comment-blanked text. */
    private fun scanFile(rel: String, code: String, scan: Scan) {
        val kinds = KotlinText.kinds(code, comments = false)
        val constants = CONST_DECL.findAll(code).associate { it.groupValues[1] to it.groupValues[2] }
        for (direct in DIRECT_GETENV.findAll(code)) {
            if (kinds[direct.range.first] == KotlinText.STRING) continue
            scan.problems += "DIRECT READ OUTSIDE THE SEAM: $rel:${KotlinText.lineOf(code, direct.range.first)} calls " +
                "getenv() directly — this scan cannot see the name it reads, so the denominator is incomplete and no " +
                "green from this run is true. Inject an EnvReader instead (kt-no-system-getenv)"
        }
        for (call in SEAM_CALL.findAll(code)) {
            if (kinds[call.range.first] == KotlinText.STRING) continue
            scanCall(rel, code, call, constants, scan)
        }
    }

    /** One seam call: a literal name, a const-backed name, a computed site, or a pass-through. */
    private fun scanCall(rel: String, code: String, call: MatchResult, constants: Map<String, String>, scan: Scan) {
        val at = call.range.first
        val openIndex = code.indexOf('(', call.range.last)
        val end = KotlinText.closeParen(code, openIndex)
        if (end == null) {
            scan.problems += "$rel:${KotlinText.lineOf(code, at)}: a seam call's argument list does not close — the " +
                "argument cannot be read"
            return
        }
        val arg = code.substring(openIndex + 1, end)
        val ident = IDENT_ARG.find(arg)?.groupValues?.get(1)
        // `EnvReader(env)` / `foo(envReader)` style pass-throughs are not reads.
        if (ident == "env" || ident == "envReader") return
        scan.sites += 1
        val where = "$rel:${KotlinText.lineOf(code, at)}"
        val literal = STRING_ARG.find(arg)?.groupValues?.get(1)
        val const = ident?.let { constants[it] }
        when {
            literal != null -> scan.names += EnvName(literal, "literal", where)
            const != null -> scan.names += EnvName(const, "const", where)
            else -> scan.computed += "$where  ${call.groupValues[1]}(${arg.trim()})"
        }
    }

    private fun List<EnvName>.associateByFirst(): Map<String, EnvName> {
        val out = linkedMapOf<String, EnvName>()
        for (env in this) out.putIfAbsent(env.name, env)
        return out
    }

    /** The enum's entry-list text, found by paren matching exactly as the checker did, or the
     *  problem that stops the parse. */
    private fun knobEntries(code: String, knobRel: String): Pair<String?, String?> {
        val decl = ENUM_DECL.find(code)
            ?: return null to "$knobRel: no `enum class Knob` declaration found — the enum has moved or been renamed, " +
                "so this run has no knob denominator and must not pass"
        val ctorEnd = KotlinText.closeParen(code, code.indexOf('(', decl.range.last + 1))
            ?: return null to "$knobRel: the Knob primary constructor could not be parsed"
        val brace = code.indexOf('{', ctorEnd)
        val bodyEnd = KotlinText.closeParen(code, brace)
        return if (bodyEnd == null) {
            null to "$knobRel: the Knob enum body could not be parsed"
        } else {
            KotlinText.splitTopLevel(code.substring(brace + 1, bodyEnd), ';')[0] to null
        }
    }

    /** The body of the entry's `listOf(...)` argument, or null with the reason it cannot be read. */
    private fun envNamesList(
        part: String,
        from: Int,
        entry: String,
        knobRel: String,
        problems: MutableList<String>,
    ): String? {
        val argsOpen = part.indexOf('(', from)
        val argsEnd = KotlinText.closeParen(part, argsOpen)
        if (argsEnd == null) {
            problems += "$knobRel: $entry argument list could not be parsed"
            return null
        }
        val listed = KotlinText.splitTopLevel(part.substring(argsOpen + 1, argsEnd), ',')
            .firstOrNull { it.trim().startsWith("listOf(") }
            ?.trim()
        if (listed == null) {
            problems += "$knobRel: $entry declares no envNames listOf(...) — its env aliases cannot be read from the source"
            return null
        }
        val innerEnd = KotlinText.closeParen(listed, listed.indexOf('('))
        if (innerEnd == null) problems += "$knobRel: $entry envNames list could not be parsed"
        return innerEnd?.let { listed.substring(listed.indexOf('(') + 1, it) }
    }

    /** The `listOf(...)` env aliases of one enum entry, appended to [names]; parse trouble to [problems]. */
    private fun entryAliases(
        part: String,
        knobRel: String,
        names: MutableList<EnvName>,
        problems: MutableList<String>,
    ) {
        val head = ENTRY_HEAD.find(part) ?: return
        val entry = head.groupValues[1]
        val listBody = envNamesList(part, head.range.last, entry, knobRel, problems) ?: return
        for (alias in KotlinText.splitTopLevel(listBody, ',')) {
            val literal = STRING_ARG.find(alias)
            if (literal != null) {
                names += EnvName(literal.groupValues[1], "knob", "Knob.$entry")
            } else if (alias.isNotBlank()) {
                problems += "$knobRel: $entry lists a non-literal env alias (${KotlinText.pyRepr(alias.trim())}) — the " +
                    "name cannot be read from the source"
            }
        }
    }

    /** Every name in a Knob entry's envNames list. */
    fun scanKnobAliases(knob: File, knobRel: String): Pair<List<EnvName>, List<String>> {
        if (!knob.isFile) {
            return emptyList<EnvName>() to listOf(
                "$knobRel: missing — its envNames lists are the largest part of the denominator, so its absence cannot pass",
            )
        }
        val (entriesText, stop) = knobEntries(KotlinText.blankComments(knob.readText()), knobRel)
        if (entriesText == null) return emptyList<EnvName>() to listOfNotNull(stop)
        val names = mutableListOf<EnvName>()
        val problems = mutableListOf<String>()
        for (part in KotlinText.splitTopLevel(entriesText, ',')) entryAliases(part, knobRel, names, problems)
        if (names.isEmpty()) {
            problems += "$knobRel: parsed 0 env aliases — refusing to pass vacuously, because a green over an empty " +
                "denominator is what this wall exists to prevent"
        }
        return names to problems
    }

    /** name -> first read site, plus the computed residue and any untrusted-parse problems. */
    fun denominator(files: List<File>, root: File, knob: File, knobRel: String): Denominator {
        val seam = scanSeam(files, root)
        val (knobNames, knobProblems) = scanKnobAliases(knob, knobRel)
        val problems = seam.problems + knobProblems
        val names = (seam.names.values + knobNames).associateByFirst()
        val all = problems.toMutableList()
        if (names.isEmpty() && problems.isEmpty()) {
            all += "parsed 0 environment variable names — refusing to pass vacuously over an empty denominator"
        }
        return Denominator(names, seam.computed, all)
    }

    private fun isCommentOrBlank(line: String): Boolean {
        val stripped = line.trim()
        return stripped.isEmpty() || stripped.startsWith("#")
    }

    /** The ENVIRONMENT VARIABLES block: the sentinel line plus the contiguous run of comment and
     *  blank lines after it. (block text, sentinel line number) — or (null, 0). */
    fun headerBlock(text: String): Pair<String?, Int> {
        val lines = text.split("\n")
        val index = lines.indexOfFirst { BLOCK_SENTINEL.containsMatchIn(it) }
        if (index < 0) return null to 0
        val block = listOf(lines[index]) + lines.drop(index + 1).takeWhile { isCommentOrBlank(it) }
        return block.joinToString("\n") to index + 1
    }

    /** The name as a whole token, CASE-SENSITIVE. */
    fun nameToken(name: String) = Regex("(?<![\\w-])${Regex.escape(name)}(?![\\w-])")

    /** The header block, with its absence or a missing precedence chain recorded; "" when absent. */
    private fun checkedBlock(text: String, surfaceRel: String, count: Int, problems: MutableList<String>): String {
        val (block, line) = headerBlock(text)
        if (block == null) {
            problems += "$surfaceRel: no `# ENVIRONMENT VARIABLES` header block — environment is the highest-precedence " +
                "config layer and the file that teaches the config does not mention it. Add the block, state the " +
                "precedence chain (env > TOML > default) in it, and describe each of the $count variables splice reads"
        } else if (!PRECEDENCE_CHAIN.containsMatchIn(block)) {
            problems += "$surfaceRel:$line: the ENVIRONMENT VARIABLES block does not state the precedence chain — a var " +
                "list that does not say env beats TOML beats default leaves the one fact an operator needs unwritten. " +
                "Write `env > TOML > default`"
        }
        return block ?: ""
    }

    /** One name's problem, or null when it is documented in the block or retired with a reason. */
    private fun disposition(name: String, env: EnvName, text: String, block: String, surfaceRel: String): String? {
        val (marked, reason) = KotlinText.retiredReason(text, name)
        return when {
            marked && reason.isEmpty() ->
                "$surfaceRel: $name is retired with NO reason — a retirement without a " +
                    "written reason is an absence wearing a label"
            marked || nameToken(name).containsMatchIn(block) -> null
            else -> {
                val outside = if (nameToken(name).containsMatchIn(text)) "" else " (not named anywhere in the file)"
                "NO DISPOSITION: $name (${env.kind}, read at ${env.where}) is not documented in the ENVIRONMENT VARIABLES " +
                    "block of $surfaceRel$outside; document it there against the precedence chain, or retire it with " +
                    "`# retired: $name — <reason>`"
            }
        }
    }

    fun audit(files: List<File>, root: File, knob: File, knobRel: String, surface: Surface): List<String> {
        val den = denominator(files, root, knob, knobRel)
        // An untrusted parse is terminal: a disposition report over a denominator that cannot be
        // trusted would be a green wearing the wrong number.
        if (den.problems.isNotEmpty()) return den.problems
        if (!surface.file.isFile) {
            return listOf(
                "${surface.rel}: disposition surface missing — a surface that cannot be read cannot document anything",
            )
        }
        val text = surface.file.readText()
        val problems = mutableListOf<String>()
        val block = checkedBlock(text, surface.rel, den.names.size, problems)
        for (name in den.names.keys.sorted()) {
            val problem = disposition(name, den.names.getValue(name), text, block, surface.rel)
            if (problem != null) problems += problem
        }
        return problems
    }
}

class EnvVarsDocumentedLawTest {
    private val map = ProjectMap.fromSystemProperties()

    @Test
    fun `every env var read through the EnvReader seam is documented in the example config's block - V4-87`() {
        val files = KotlinText.kotlinFiles(map)
        assertTrue(
            files.size > 10,
        ) {
            "the map yielded ${files.size} production file(s) — the walk is broken, and a law that reads no files passes vacuously."
        }
        val knob = File(map.mainSources(":core"), EnvVarsDocumented.KNOB_IN_CORE)
        val problems = EnvVarsDocumented.audit(
            files,
            map.root,
            knob,
            KotlinText.rel(map, knob),
            EnvVarsDocumented.Surface(
                EnvVarsDocumented.EXAMPLE_CONFIG,
                File(map.root, EnvVarsDocumented.EXAMPLE_CONFIG),
            ),
        )
        assertTrue(problems.isEmpty()) {
            problems.joinToString(separator = "\n  - ", prefix = "ENV VARS DOCUMENTED (V4-87) violated:\n  - ")
        }
    }

    /** The synthetic tree the red proof writes into: the seam source, the computed-argument source,
     *  the Knob enum and the example config, each replaceable per arm. */
    private class Tree(val root: File) {
        val seam = File(root, SEAM_SOURCE_REL)
        val computed = File(root, COMPUTED_SOURCE_REL)
        val knob = File(root, KNOB_REL)
        val surface = File(root, EnvVarsDocumented.EXAMPLE_CONFIG)
        private val synthetic = ProjectMap.parse(root, ":app=app;:core=core", setOf("build"))

        fun write(
            doc: String,
            seamText: String = SEAM_SOURCE,
            knobText: String = KNOB_SOURCE,
            computedText: String? = COMPUTED_SOURCE,
            extra: Pair<String, String>? = null,
        ) {
            seam.parentFile.mkdirs()
            seam.writeText(seamText)
            knob.parentFile.mkdirs()
            knob.writeText(knobText)
            if (computedText == null) {
                computed.delete()
            } else {
                computed.parentFile.mkdirs()
                computed.writeText(computedText)
            }
            if (extra != null) File(root, extra.first).apply { parentFile.mkdirs() }.writeText(extra.second)
            surface.parentFile.mkdirs()
            surface.writeText(doc)
        }

        fun files() = KotlinText.kotlinFiles(synthetic)

        fun audit() = EnvVarsDocumented.audit(
            files(),
            root,
            knob,
            KNOB_REL,
            EnvVarsDocumented.Surface(EnvVarsDocumented.EXAMPLE_CONFIG, surface),
        )

        fun den() = EnvVarsDocumented.denominator(files(), root, knob, KNOB_REL)
    }

    @Test
    fun `the law can actually fail - the denominator - V4-87`(@TempDir root: File) {
        with(Tree(root)) {
            write(COMPLIANT_DOC)
            assertEquals(
                emptyList<String>(),
                audit(),
                "compliant tree must be GREEN (literal, const, .invoke, knob alias, two reasoned retirements)",
            )
            var d = den()
            assertEquals(emptyList<String>(), d.problems, "the compliant denominator must be trusted")
            assertEquals(
                listOf(
                    "CLAUDEX_DEBUG",
                    "CODEX_PROXY_DEBUG",
                    "CODEX_PROXY_PORT",
                    "GROK_PROXY_PORT",
                    "OPENROUTER_API_KEY",
                    "SPLICE_CONFIG",
                    "XDG_CONFIG_HOME",
                ),
                d.names.keys.sorted(),
                "the denominator: three literal/const/.invoke reads and four knob aliases; a read inside a comment never enters it",
            )
            assertEquals(
                1,
                d.computed.size,
                "exactly the one real computed site; a seam-shaped call inside a string literal is not a seam site: ${d.computed}",
            )
            assertTrue(d.computed[0].contains("env(envVar)"), d.computed[0])

            // The BORING case: one literal, one knob alias, and the count must come out at two.
            write(BORING_DOC, BORING_SEAM, BORING_KNOB, null)
            assertEquals(emptyList<String>(), audit(), "the one-var tree must be GREEN")
            d = den()
            assertEquals(listOf("CODEX_PROXY_PORT", "SPLICE_CONFIG"), d.names.keys.sorted())
            assertEquals(emptyList<String>(), d.computed)
        }
    }

    @Test
    fun `the law can actually fail - the two mutations - V4-87`(@TempDir root: File) {
        with(Tree(root)) {
            // The mutation this row requires: a synthetic literal read injected into a temp copy.
            val mutated = SEAM_SOURCE.replace(
                "val config = env(\"SPLICE_CONFIG\")",
                "val config = env(\"SPLICE_FAKE_NEW_VAR\") ?: env(\"SPLICE_CONFIG\")",
            )
            assertTrue(mutated != SEAM_SOURCE, "the seam mutation did not apply")
            write(COMPLIANT_DOC, mutated)
            assertHit(audit(), "SPLICE_FAKE_NEW_VAR") { "a synthetic literal read must be RED BY NAME" }

            val mutatedKnob = KNOB_SOURCE.replace(
                "GROK_PORT(\"grokPort\", KnobKind.NUMBER, listOf(\"GROK_PROXY_PORT\")),",
                "GROK_PORT(\"grokPort\", KnobKind.NUMBER, listOf(\"GROK_PROXY_PORT\", \"SPLICE_FAKE_ALIAS\")),",
            )
            assertTrue(mutatedKnob != KNOB_SOURCE, "the knob mutation did not apply")
            write(COMPLIANT_DOC, SEAM_SOURCE, mutatedKnob)
            assertHit(audit(), "SPLICE_FAKE_ALIAS") { "a synthetic knob alias must be RED BY NAME" }
        }
    }

    @Test
    fun `the law can actually fail - dispositions and refusals - V4-87`(@TempDir root: File) {
        with(Tree(root)) {
            write(RETIRED_NOREASON_DOC)
            var hits = audit()
            assertHit(hits, "GROK_PROXY_PORT", "NO reason") { "a retirement with an empty reason must be RED by name" }
            assertEquals(
                1,
                hits.count { it.contains("GROK_PROXY_PORT") },
                "an unreasoned retirement is ONE problem, not a duplicate pair, got: $hits",
            )

            write(NO_BLOCK_DOC)
            assertHit(audit(), "no `# ENVIRONMENT VARIABLES` header block") { "a missing header block must be RED" }

            write(NO_CHAIN_DOC)
            assertHit(audit(), "precedence chain") { "a block without the precedence chain must be RED" }

            write(OUTSIDE_BLOCK_DOC)
            hits = audit()
            assertHit(
                hits,
                "NO DISPOSITION: SPLICE_CONFIG",
            ) { "a var named only OUTSIDE the block must be RED by name" }
            assertTrue(
                hits.none { it.contains("not named anywhere in the file") && it.contains("SPLICE_CONFIG") },
                "a var named outside the block must NOT be reported as absent from the file",
            )

            write(
                COMPLIANT_DOC,
                SEAM_SOURCE,
                KNOB_SOURCE,
                COMPUTED_SOURCE,
                "app/src/main/kotlin/splice/app/cli/Sneaky.kt" to DIRECT_GETENV_SOURCE,
            )
            assertHit(audit(), "DIRECT READ OUTSIDE THE SEAM") { "a direct System.getenv call must be RED" }
            File(root, "app/src/main/kotlin/splice/app/cli/Sneaky.kt").delete()

            write(COMPLIANT_DOC, NO_SEAM_SOURCE, KNOB_SOURCE, null)
            assertHit(audit(), "has lost the seam") { "zero seam call sites must be RED" }

            write(COMPLIANT_DOC, SEAM_SOURCE, EMPTY_KNOB_SOURCE)
            assertHit(audit(), "refusing to pass vacuously") { "zero knob aliases must be RED" }

            write(COMPLIANT_DOC)
            surface.delete()
            assertHit(audit(), "disposition surface missing") { "a missing surface must be RED" }
        }
    }

    private companion object {
        const val SEAM_SOURCE_REL = "app/src/main/kotlin/splice/app/cli/Fixture.kt"
        const val COMPUTED_SOURCE_REL = "app/src/main/kotlin/splice/app/cli/Computed.kt"
        const val KNOB_REL = "core/src/main/kotlin/${EnvVarsDocumented.KNOB_IN_CORE}"

        const val SEAM_SOURCE = """package splice.app.cli

internal object Fixture {
    fun paths(env: EnvReader = EnvReader(System::getenv)): String? {
        // env("COMMENTED_OUT_VAR") — a commented read is not a read.
        val config = env("SPLICE_CONFIG")
        val openRouter = env(OPENROUTER_KEY)
        val explicit = env.invoke("XDG_CONFIG_HOME")
        return config ?: openRouter ?: explicit
    }
}

private const val OPENROUTER_KEY = "OPENROUTER_API_KEY"
"""
        const val COMPUTED_SOURCE = """package splice.app.cli

internal object Computed {
    fun key(envVar: String, env: EnvReader): String? = env(envVar)

    // The McpSharing.kt:171 shape: a seam-shaped call inside a STRING literal.
    fun complain(): String = "malformed env (expected string values)"
}
"""
        const val KNOB_SOURCE = """package splice.core.config

public enum class Knob(
    public val key: String,
    public val kind: KnobKind,
    public val envNames: List<String>,
) {
    PORT("port", KnobKind.NUMBER, listOf("CODEX_PROXY_PORT")),
    // A prose comment with (parens) and "quotes" a naive walk would choke on.
    DEBUG("debug", KnobKind.BOOL, listOf("CLAUDEX_DEBUG", "CODEX_PROXY_DEBUG")),
    GROK_PORT("grokPort", KnobKind.NUMBER, listOf("GROK_PROXY_PORT")),
}
"""
        const val COMPLIANT_DOC = """[daemon]
control_port = 3096

# ── ENVIRONMENT VARIABLES ──────────────────────────────────────────────────────
# Precedence: env > TOML > default. An env var set in the daemon's environment wins
# over anything in this file.
#   SPLICE_CONFIG        absolute path to splice.toml; overrides the XDG lookup
#   XDG_CONFIG_HOME      base for the default config lookup (~/.config when unset)
#   OPENROUTER_API_KEY   OpenRouter bearer, read by `splice setup` detection
#   CODEX_PROXY_PORT     the codex head's listen port (knob `port`)
#   CLAUDEX_DEBUG        verbose daemon logging (knob `debug`)
# retired: CODEX_PROXY_DEBUG — superseded by CLAUDEX_DEBUG in v0.3.0; still read as an alias
# retired: GROK_PROXY_PORT — the grok head takes its port from [heads.*.port]

[providers.openrouter]
dialect = "openai-chat"
"""
        val NO_BLOCK_DOC = COMPLIANT_DOC.replace(
            "# ── ENVIRONMENT VARIABLES ──────────────────────────────────────────────────────\n",
            "",
        )
        val NO_CHAIN_DOC = COMPLIANT_DOC.replace(
            "# Precedence: env > TOML > default. An env var set in the daemon's environment wins\n# over anything in this file.\n",
            "# Set these in the daemon's environment.\n",
        )

        // SPLICE_CONFIG named only OUTSIDE the block — past a live TOML line, which is what ends the
        // block: the drift a file-wide token search misses.
        val OUTSIDE_BLOCK_DOC = COMPLIANT_DOC.replace("#   SPLICE_CONFIG        absolute path to splice.toml; overrides the XDG lookup\n", "") +
            "# SPLICE_CONFIG is mentioned down here, in an unrelated provider comment.\n"
        val RETIRED_NOREASON_DOC = COMPLIANT_DOC.replace(
            "# retired: GROK_PROXY_PORT — the grok head takes its port from [heads.*.port]",
            "# retired: GROK_PROXY_PORT —",
        )

        const val BORING_SEAM = """package splice.app.cli

internal object Fixture {
    fun path(env: EnvReader): String? = env("SPLICE_CONFIG")
}
"""
        const val BORING_KNOB = """package splice.core.config

public enum class Knob(
    public val key: String,
    public val envNames: List<String>,
) {
    PORT("port", listOf("CODEX_PROXY_PORT")),
}
"""
        const val BORING_DOC = """# ENVIRONMENT VARIABLES
# Precedence: env > TOML > default.
#   SPLICE_CONFIG      absolute path to splice.toml
#   CODEX_PROXY_PORT   the codex head's listen port
"""
        const val DIRECT_GETENV_SOURCE = """package splice.app.cli

internal object Sneaky {
    fun home(): String? = System.getenv("HOME")
}
"""
        const val NO_SEAM_SOURCE = """package splice.app.cli

internal object Inert {
    fun nothing(): String = "no environment here"
}
"""
        const val EMPTY_KNOB_SOURCE = """package splice.core.config

public enum class Knob(
    public val key: String,
    public val envNames: List<String>,
) {
}
"""
    }
}
