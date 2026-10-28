// Failure-handling slice 8: the API reads replies, providers declare deviations, and the shared decision path knows
// no head by name. The build's module map is the denominator, including newly added dialects and providers.
package splice.quality

import com.lemonappdev.konsist.api.Konsist
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal object FailurePlacement {
    /** Implementations belong to their API's module or their provider's module, never the shared executor. */
    fun reader(module: String, type: String): String? = when {
        type == "ReplyReader" && !module.startsWith(":integrations-dialects-") ->
            "$module implements ReplyReader outside a dialect module"
        type == "ProviderDeviation" && !module.startsWith(":integrations-providers-") ->
            "$module implements ProviderDeviation outside a provider module"
        else -> null
    }

    /** The declaration/package exemptions in the two source walls name these actual authors. A second declaration
     *  elsewhere cannot acquire the exemption merely by copying the class name and package. */
    val AUTHORS = mapOf(
        "SpliceLine" to "core/src/main/kotlin/splice/core/failure/SpliceLine.kt",
        "SpliceLines" to "core/src/main/kotlin/splice/core/failure/SpliceLines.kt",
        "ClientWire" to "core/src/main/kotlin/splice/core/failure/ClientWire.kt",
    )
    private val AUTHOR_DECL = Regex("\\b(?:class|object)\\s+(SpliceLines?|ClientWire)\\b")
    private val LITERAL = Regex("\"\"\"[\\s\\S]*?\"\"\"|\"(?:\\\\.|[^\"\\\\])*\"")
    private val HEAD_COMMAND = Regex("\\bclaude-[a-z][a-z0-9_-]*")

    fun authors(path: String, text: String): List<String> =
        AUTHOR_DECL.findAll(KotlinText.blankCommentsAndStrings(text)).mapNotNull { match ->
            val name = match.groupValues[1]
            AUTHORS[name]?.takeUnless { it == path }?.let { "$path declares $name; its sole author is $it" }
        }.toList()

    /** The provider vocabulary comes from the module map. Concrete launch commands are head identities too; a
     *  dynamic head name carried as data is not a literal. Comments are deliberately outside this check. */
    fun headNames(path: String, text: String, providers: Set<String>): List<String> =
        LITERAL.findAll(KotlinText.blankComments(text)).mapNotNull { match ->
            val literal = match.value.removeSurrounding("\"\"\"").removeSurrounding("\"").lowercase()
            // Protocol field names such as anthropic-ratelimit-* are wire facts, not head identities.
            if (literal in providers || HEAD_COMMAND.matches(literal)) {
                val line = text.take(match.range.first).count { it == '\n' } + 1
                "$path:$line names a head or provider in shared failure/retry/hold code"
            } else {
                null
            }
        }.toList()
}

class FailureReadersLawTest {
    private val map = ProjectMap.fromSystemProperties()

    @Test
    fun `reply readers and provider deviations live in their owning modules`() {
        val implementations = map.modules.sorted().filter { map.mainSources(it).isDirectory }.flatMap { module ->
            Konsist.scopeFromDirectory("${map.relativeDir(module)}/src/main/kotlin")
                .classesAndObjects(true, true)
                .flatMap { declaration ->
                    declaration.parents(true).map { it.name.substringAfterLast('.') }
                        .filter { it == "ReplyReader" || it == "ProviderDeviation" }
                        .distinct().map { module to it }
                }
        }
        assertTrue(
            implementations.count { it.second == "ReplyReader" } >= 3,
            "the API-reader census is empty or incomplete",
        )
        assertTrue(implementations.any { it.second == "ProviderDeviation" }, "the provider-deviation census is empty")
        val problems = implementations.mapNotNull { (module, type) -> FailurePlacement.reader(module, type) }
        assertTrue(problems.isEmpty()) { problems.joinToString("\n") }
    }

    @Test
    fun `shared failure retry and hold code carries identities as data and the wire authors stay unique`() {
        val files = KotlinText.kotlinFiles(map)
        assertTrue(files.size > 100, "the production census is incomplete")
        val providers = map.modules.filter { it.startsWith(":integrations-providers-") }
            .map { it.removePrefix(":integrations-providers-") }.toSet()
        assertTrue(providers.isNotEmpty(), "the module map names no providers")
        val problems = files.flatMap { file ->
            val path = KotlinText.rel(map, file)
            val text = file.readText()
            val shared = path.startsWith("core/src/main/kotlin/splice/core/failure/") ||
                path.startsWith("integrations/upstream/src/main/kotlin/splice/upstream/failure/") ||
                path.startsWith("integrations/upstream/src/main/kotlin/splice/upstream/retry/") ||
                path.startsWith("features/turns/src/main/kotlin/splice/head/hold/")
            FailurePlacement.authors(
                path,
                text,
            ) + if (shared) FailurePlacement.headNames(path, text, providers) else emptyList()
        }
        FailurePlacement.AUTHORS.values.forEach {
            assertTrue(map.root.resolve(it).isFile, "the canonical wire author is missing: $it")
        }
        assertTrue(problems.isEmpty()) { problems.joinToString("\n") }
    }

    @Test
    fun `failure packages remain in the concentration census`() {
        val packages = Concentration.collect(map).map { it.pkg }.toSet()
        val required = setOf(
            "splice.core.failure",
            "splice.head.hold",
            "splice.provider.codex.failure",
            "splice.dialect.anthropic.failure",
            "splice.dialect.chat.failure",
            "splice.dialect.responses.failure",
        )
        assertTrue(packages.containsAll(required)) {
            "failure packages missing from concentration census: ${required - packages}"
        }
    }

    @Test
    fun `a reader in a provider and a deviation in a dialect are refused`() {
        assertEquals(null, FailurePlacement.reader(":integrations-dialects-anthropic", "ReplyReader"))
        assertEquals(null, FailurePlacement.reader(":integrations-providers-kimi", "ProviderDeviation"))
        assertTrue(FailurePlacement.reader(":integrations-providers-kimi", "ReplyReader") != null)
        assertTrue(FailurePlacement.reader(":integrations-dialects-anthropic", "ProviderDeviation") != null)
        assertTrue(FailurePlacement.reader(":features-turns", "ReplyReader") != null)
    }

    @Test
    fun `a copied author and a head key in generic retry code are refused`() {
        assertTrue(FailurePlacement.authors("other/SpliceLines.kt", "class SpliceLines").isNotEmpty())
        assertEquals(
            emptyList<String>(),
            FailurePlacement.authors(FailurePlacement.AUTHORS.getValue("SpliceLines"), "class SpliceLines"),
        )
        assertTrue(FailurePlacement.headNames("retry/Fake.kt", "val head = \"kimi\"", setOf("kimi")).isNotEmpty())
        assertTrue(FailurePlacement.headNames("hold/Fake.kt", "val head = \"claude-kimi\"", emptySet()).isNotEmpty())
        assertEquals(
            emptyList<String>(),
            FailurePlacement.headNames("retry/Fake.kt", "val head = facts.head // kimi", setOf("kimi")),
        )
    }
}
