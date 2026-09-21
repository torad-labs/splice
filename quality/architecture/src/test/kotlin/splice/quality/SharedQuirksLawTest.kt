// NEW: DR-1xx — a SHARED *Quirks type may not carry a vendor-identity default (ported from
// checks/config/shared-quirks-no-vendor-defaults.ts, restructure PR 6).
//
// WHY THIS EXISTS. Every dialect that shares the responses wire also shares one `*Quirks` data
// class, and a default on that class is a default for EVERY dialect that uses it. A vendor fact —
// a model-id regex, a vendor header name, a vendor host, a measured lite wire byte — parked in the
// shared class as a default is therefore not a convenience: it is one dialect's fact silently
// applied to all of them, and the next dialect added inherits it without anyone deciding anything.
// The class is where the SHAPE of the wire is declared; which vendor's shape it is belongs at the
// call site, which is what this law forces by refusing the default.
//
// THE FOUR SHAPES THAT FAIL, and each is a different way the same fact leaks in:
//   · a `Regex` default (or a field NAMED *Models / *ModelRegex) — a model-id fact;
//   · a field whose name contains `Header` with a default, or any default shaped like a vendor
//     header name (`"x-..."`) — a header-name fact;
//   · a default containing a vendor HOST — a routing fact;
//   · a lite-gated default carrying a measured wire byte — `liteTextVerbosity = "low"`,
//     `sendClientMetadata = true`, `liteParallelToolCalls = true`. `false` and `null` are OMIT
//     (the vendor's absence), not a measured byte, so they stay green.
//
// WHAT IS GREEN: `null` (an explicit "no vendor fact here"), a plain absence of a default, and any
// default that is not shaped like one of the four above — `minImageEdgePx: Int? = null` and
// `extra: String? = null` in the compliant fixture are exactly that, and they are the boring cases
// a checker like this gets wrong by over-matching.
//
// SCOPE IS DERIVED, not listed: every .kt under src/main of every `:dialects-*` module the build
// declares. A provider module (`:providers-codex`, ...) is NOT in scope — its own `CodexQuirks` may
// hold vendor facts, because it IS the vendor — and the red proof pins that asymmetry with a
// synthetic map holding both, so a future widening of the scope cannot silently pull providers in.
package splice.quality

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

internal object SharedQuirks {
    private val CLASS_HEAD = Regex("public data class (\\w+Quirks)\\s*\\(")
    private val HEADER_DEFAULT = Regex("^[\"']x-[A-Za-z0-9-]+[\"']$", RegexOption.IGNORE_CASE)
    private val HOST_DEFAULT = Regex(
        "https?://|api\\.openai\\.|api\\.x\\.ai|anthropic\\.com|openrouter\\.ai|moonshot\\.cn",
        RegexOption.IGNORE_CASE,
    )
    private val PARAM = Regex(
        "\\bval\\s+(\\w+)\\s*:\\s*([^=]+?)(?:\\s*=\\s*(.*))?\\s*$",
        RegexOption.DOT_MATCHES_ALL,
    )
    private val WHITESPACE = Regex("\\s+")
    private val QUOTES = Regex("^[\"']|[\"']$")
    private const val UNSHAPED = "default is not a model id, header name, vendor host, or lite-gated measured wire byte"

    /** One primary-constructor field: name, declared type text, default text (null = no default). */
    data class Field(val name: String, val typeText: String, val default: String?)

    fun dialectModules(map: ProjectMap): List<String> =
        map.modules.filter { it.startsWith(":dialects-") }.sorted()

    /** Every .kt under src/main of every dialect module, in path order. */
    fun dialectQuirkFiles(map: ProjectMap): List<File> =
        dialectModules(map).flatMap { module ->
            val main = File(map.dir(module), "src/main")
            if (main.isDirectory) {
                main.walkTopDown().filter { it.isFile && it.extension == "kt" }.sortedBy { it.path }.toList()
            } else {
                emptyList()
            }
        }

    private fun parseParams(body: String): List<Field> =
        KotlinText.splitTopLevel(body, ',', angles = true).mapNotNull { raw ->
            val text = raw.trim()
            if (text.isEmpty()) return@mapNotNull null
            val match = PARAM.find(text) ?: return@mapNotNull null
            val default = match.groups[3]?.value?.trim()
            Field(match.groupValues[1], match.groupValues[2].trim(), default)
        }

    fun parseSharedQuirks(source: String): List<Pair<String, List<Field>>> =
        CLASS_HEAD.findAll(source).mapNotNull { m ->
            val span = KotlinText.balancedSpan(source, m.range.last, '(', ')') ?: return@mapNotNull null
            m.groupValues[1] to parseParams(source.substring(span.bodyStart, span.closerAt))
        }.toList()

    private fun isNullDefault(d: String?): Boolean = d != null && d.trim() == "null"

    /** The shape label, or null if this field is not a vendor identity fact. */
    private fun vendorShaped(name: String, typeText: String, def: String?): String? {
        val modelShaped = typeText.replace(WHITESPACE, "").contains("Regex") || name.endsWith("Models")
        return when {
            modelShaped || name.contains("ModelRegex") -> "model-id"
            name.contains("Header") -> "header-name"
            def == null -> null
            else -> defaultShaped(def.trim())
        }
    }

    /** A default that spells a header name (`x-…`) or a vendor host. */
    private fun defaultShaped(stripped: String): String? {
        val inner = stripped.replace(QUOTES, "")
        return when {
            inner.lowercase().startsWith("x-") && HEADER_DEFAULT.matches("\"$inner\"") -> "header-name"
            HOST_DEFAULT.containsMatchIn(stripped) -> "vendor-host"
            else -> null
        }
    }

    /** A lite-gated default that encodes a measured vendor wire byte: the name contains lite
     *  (responsesLite*, liteTextVerbosity, emitEmptyLiteInstructions, liteParallelToolCalls) or is
     *  sendClientMetadata, which is lite-gated without the prefix. false and null are omit. */
    private fun liteGatedWireByte(name: String, def: String?): String? {
        if (def == null || isNullDefault(def)) return null
        val liteGated = name.lowercase().contains("lite") || name == "sendClientMetadata"
        return if (def.trim() == "false" || !liteGated) null else "lite-wire-byte"
    }

    /** (kind, detail) where kind is required, clean-null, clean-unshaped, or fail. */
    fun classify(name: String, typeText: String, def: String?): Pair<String, String> {
        val shape = vendorShaped(name, typeText, def) ?: liteGatedWireByte(name, def)
        return when {
            def == null -> "required" to "no default"
            isNullDefault(def) -> "clean-null" to ("null default" + if (shape != null) " ($shape)" else "")
            shape == null -> "clean-unshaped" to UNSHAPED
            else -> "fail" to "$shape default: $def"
        }
    }

    fun checkSource(label: String, source: String): List<String> {
        val problems = mutableListOf<String>()
        for ((className, fields) in parseSharedQuirks(source)) {
            if (fields.isEmpty()) {
                problems += "$label: $className has no parsed fields — refusing to pass vacuously"
                continue
            }
            for ((name, typeText, def) in fields) {
                val (kind, detail) = classify(name, typeText, def)
                if (kind == "fail") problems += "$label: $className.$name $detail"
            }
        }
        return problems
    }

    fun checkTree(map: ProjectMap): List<String> {
        val problems = mutableListOf<String>()
        var saw = false
        for (path in dialectQuirkFiles(map)) {
            val source = path.readText()
            if (parseSharedQuirks(source).isEmpty()) continue
            saw = true
            problems += checkSource(KotlinText.rel(map, path), source)
        }
        if (!saw) problems += "no shared *Quirks data class under any :dialects-* module's src/main"
        return problems
    }
}

class SharedQuirksLawTest {
    private val map = ProjectMap.fromSystemProperties()

    @Test
    fun `a shared Quirks type carries no vendor-identity default`() {
        assertTrue(SharedQuirks.dialectModules(map).size >= 3) {
            "found ${SharedQuirks.dialectModules(
                map,
            ).size} dialect module(s) — the map is broken, and a law that reads no dialects passes vacuously."
        }
        val problems = SharedQuirks.checkTree(map)
        assertTrue(problems.isEmpty()) {
            problems.joinToString(separator = "\n  - ", prefix = "SHARED QUIRKS VENDOR DEFAULTS violated:\n  - ")
        }
    }

    @Test
    fun `the law can actually fail - each of the four shapes by field name`() {
        fun has(hits: List<String>, needle: String) = hits.any { it.contains(needle) }
        assertEquals(
            emptyList<String>(),
            SharedQuirks.checkSource("compliant", COMPLIANT),
            "compliant null vendor knobs plus a boring null extra must be GREEN",
        )
        assertTrue(
            has(SharedQuirks.checkSource("regex", REGEX_VIOLATION), "effortMaxRejectModelRegex"),
            "synthetic Regex default must be RED by field name",
        )
        assertTrue(
            has(SharedQuirks.checkSource("header", HEADER_VIOLATION), "responsesLiteHeader"),
            "synthetic header-name default must be RED by field name",
        )
        assertTrue(
            has(SharedQuirks.checkSource("host", HOST_VIOLATION), "baseUrl"),
            "synthetic vendor-host default must be RED by field name",
        )
        assertTrue(
            has(SharedQuirks.checkSource("verbosity", LITE_VERBOSITY_VIOLATION), "liteTextVerbosity"),
            "synthetic liteTextVerbosity default must be RED by field name",
        )
        assertTrue(
            has(SharedQuirks.checkSource("metadata", METADATA_VIOLATION), "sendClientMetadata"),
            "synthetic sendClientMetadata true default must be RED by field name",
        )
        assertTrue(
            has(SharedQuirks.checkSource("parallel-on", PARALLEL_ON_VIOLATION), "liteParallelToolCalls"),
            "synthetic liteParallelToolCalls true default must be RED by field name",
        )
        assertEquals(
            listOf("empty: EmptyQuirks has no parsed fields — refusing to pass vacuously"),
            SharedQuirks.checkSource("empty", "public data class EmptyQuirks()\n"),
        )
    }

    // The scope asymmetry: a dialect's shared class is graded, a provider's own class is not.
    @Test
    fun `providers are out of scope and dialects are in - through the build's map`(@TempDir root: File) {
        val dialect = File(root, "dialects/openai-responses/src/main/kotlin").apply { mkdirs() }
        val vendor = File(root, "providers/codex/src/main/kotlin").apply { mkdirs() }
        val synthetic = ProjectMap.parse(
            root,
            ":dialects-openai-responses=dialects/openai-responses;:providers-codex=providers/codex",
            setOf("build"),
        )
        File(dialect, "ResponsesQuirks.kt").writeText(COMPLIANT)
        File(vendor, "CodexQuirks.kt").writeText(VENDOR_FILE)
        assertEquals(
            emptyList<String>(),
            SharedQuirks.checkTree(synthetic),
            "a compliant dialect beside a vendor Regex must be GREEN",
        )
        File(dialect, "ResponsesQuirks.kt").writeText(REGEX_VIOLATION)
        assertEquals(
            listOf(
                "dialects/openai-responses/src/main/kotlin/ResponsesQuirks.kt: ResponsesQuirks.effortMaxRejectModelRegex model-id default: Regex(\"mini\", RegexOption.IGNORE_CASE)",
            ),
            SharedQuirks.checkTree(synthetic),
            "the dialect's Regex default must be RED by file and field",
        )
        File(dialect, "ResponsesQuirks.kt").delete()
        assertEquals(
            listOf("no shared *Quirks data class under any :dialects-* module's src/main"),
            SharedQuirks.checkTree(synthetic),
            "no shared class at all refuses to pass",
        )
    }

    private companion object {
        const val COMPLIANT = """
public data class ResponsesQuirks(
    val providerTag: String,
    val summaryRejectModelRegex: Regex? = null,
    val effortMaxRejectModelRegex: Regex? = null,
    val responsesLiteHeader: String? = null,
    val liteTextVerbosity: String? = null,
    val sendClientMetadata: Boolean = false,
    val liteParallelToolCalls: Boolean = false,
    val minImageEdgePx: Int? = null,
    val extra: String? = null,
)
"""
        const val REGEX_VIOLATION = """
public data class ResponsesQuirks(
    val providerTag: String,
    val effortMaxRejectModelRegex: Regex? = Regex("mini", RegexOption.IGNORE_CASE),
)
"""
        const val HEADER_VIOLATION = """
public data class ResponsesQuirks(
    val providerTag: String,
    val responsesLiteHeader: String? = "x-openai-internal-codex-responses-lite",
)
"""
        const val HOST_VIOLATION = """
public data class ResponsesQuirks(
    val providerTag: String,
    val baseUrl: String? = "https://api.openai.com/v1",
)
"""
        const val LITE_VERBOSITY_VIOLATION = """
public data class ResponsesQuirks(
    val providerTag: String,
    val liteTextVerbosity: String? = "low",
)
"""
        const val METADATA_VIOLATION = """
public data class ResponsesQuirks(
    val providerTag: String,
    val sendClientMetadata: Boolean = true,
)
"""
        const val PARALLEL_ON_VIOLATION = """
public data class ResponsesQuirks(
    val providerTag: String,
    val liteParallelToolCalls: Boolean = true,
)
"""
        const val VENDOR_FILE = """
public data class CodexQuirks(
    val effortMaxRejectModelRegex: Regex? = Regex("mini", RegexOption.IGNORE_CASE),
)
"""
    }
}
