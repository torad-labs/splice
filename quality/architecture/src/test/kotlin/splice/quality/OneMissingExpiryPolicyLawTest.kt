// NEW: SH-01's structural half — ONE missing-expiry policy, and a denominator that comes from the
// tree (ported from the sh_01_missing_expiry_synthesized wall, restructure PR 6 §2.7).
//
// WHY THIS EXISTS. A credential with no usable expiry is cached forever: no proactive refresh, and
// the first signal is a mid-turn 401. Grok was fixed first (mtime + 4h), codex had the identical
// hole, and kimi's `?: 0L` produced a refresh PER CALL instead of one. The fix was one shared
// helper, splice.core.auth.CredentialExpiry, so that "a fourth provider cannot invent a fourth
// behaviour" — which is a claim about providers that do not exist yet, and therefore a claim no
// list of file paths can make.
//
// THE DENOMINATOR IS THE SWEPT TREE, not three named files. The wall this replaces opened
// CodexAuthProvider.kt, KimiAuthProvider.kt and GrokAuthProvider.kt by path. There are FIVE
// provider modules on disk — muse and openai were never looked at — so the wall's "one policy
// everywhere" was measured over the providers someone remembered to list. This law enumerates
// every *AuthProvider.kt the project map sweeps and gives each one a DISPOSITION: it derives its
// expiry through CredentialExpiry, or it carries a dated NO-EXPIRY-EXEMPT marker with a written
// reason. Nothing is silently absent, and a new provider is a violation the day it is added.
//
// THE BEHAVIOUR is already pinned per provider and stays there: CodexAuthTest `non-jwt access
// token ages out at the synthesized ceiling - SH-01`, GrokAuthProviderTest's three synthesized-
// expiry arms, and KimiAuthProviderTest `missing expires_at synthesizes one ceiling - one refresh
// across N calls - SH-01`. This file grades only what those cannot: that there is ONE rule.
//
// TWO BANS carry the rest of the wall's text. A second synthetic-TTL constant anywhere outside the
// helper is the drift the helper was created to end. And a lifetime converted to millis by hand —
// `expiresIn * 1000` — is DR-177's defect: the multiply wraps for a hostile expires_in and the sum
// wraps sooner, yielding a large NEGATIVE instant, so the credential reads expired on every turn,
// every turn refreshes, and the refresh returns the same bad field. Both bans read comment-stripped
// code, which is why GrokOAuth.kt's comment recording the old spelling is not a violation.
package splice.quality

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal object OneMissingExpiryPolicy {
    const val HELPER: String = "CredentialExpiry"
    const val TTL: String = "SYNTHETIC_EXPIRY_TTL_MS"
    const val HELPER_FILE: String = "core/src/main/kotlin/splice/core/auth/SynthesizedExpiry.kt"

    /** Any auth provider under any provider module — the set is read from the tree, never listed. */
    private val AUTH_PROVIDER = Regex("^providers/[^/]+/src/main/.*/[A-Za-z]*AuthProvider\\.kt$")

    /** The disposition a provider writes when the policy does not apply to its credential kind. */
    private val EXEMPT = Regex("//\\s*NO-EXPIRY-EXEMPT\\[(\\d{4}-\\d{2}-\\d{2})]:(.*)")

    /** DR-177's defect in its source form, either operand order. */
    private val HAND_ROLLED = Regex(
        "\\b(expiresIn\\w*|lifetimeSeconds|expiresInS)\\s*\\*\\s*1000" +
            "|1000\\s*\\*\\s*\\b(expiresIn\\w*|lifetimeSeconds)",
    )

    /** A second copy of the one TTL. `SYNTHETIC_SIGNATURE` and friends are not this. */
    private val PRIVATE_TTL = Regex("const\\s+val\\s+\\w*(SYNTHETIC\\w*EXPIRY|EXPIRY\\w*TTL)\\w*")

    /** Pure: repo-relative path -> source text. */
    fun audit(sources: Map<String, String>): List<String> {
        val problems = mutableListOf<String>()
        val providers = sources.keys.filter { AUTH_PROVIDER.matches(it) }.sorted()
        if (providers.isEmpty()) {
            problems += "no *AuthProvider.kt under providers/ was swept — this law's denominator comes from the " +
                "tree, so an empty sweep means the extractor or the project map broke, not that the policy holds"
        }
        problems += helperProblems(sources[HELPER_FILE])
        providers.forEach { rel -> problems += dispositionProblems(rel, sources.getValue(rel)) }
        sources.forEach { (rel, raw) -> if (rel != HELPER_FILE) problems += banProblems(rel, raw) }
        return problems
    }

    private fun helperProblems(helper: String?): List<String> = when {
        helper == null ->
            listOf(
                "$HELPER_FILE is missing — the one policy has no home, and every provider below would " +
                    "grade as covered against a helper that is not there",
            )
        !helper.contains("const val $TTL") ->
            listOf("$HELPER_FILE no longer declares $TTL — the shared ceiling is what the providers consume")
        else -> emptyList()
    }

    private fun dispositionProblems(rel: String, raw: String): List<String> {
        val consumes = KotlinText.stripComments(raw).contains("$HELPER.")
        val exempt = EXEMPT.find(raw)
        val reason = exempt?.groupValues?.get(2)?.trim().orEmpty()
        return when {
            consumes && exempt != null -> listOf(
                "$rel both derives its expiry through $HELPER and claims NO-EXPIRY-EXEMPT — one disposition per " +
                    "provider, or a reader cannot tell which one is true",
            )
            consumes -> emptyList()
            exempt != null && reason.isEmpty() -> listOf(
                "$rel carries a NO-EXPIRY-EXEMPT marker with no reason — a blank reason is an absence wearing a " +
                    "label; say which credential kind has no expiry to age out and why",
            )
            exempt != null -> emptyList()
            else -> listOf(
                "$rel derives no expiry through $HELPER and carries no NO-EXPIRY-EXEMPT marker. A credential with " +
                    "no usable expiry is cached forever — no proactive refresh, first signal a mid-turn 401. Route " +
                    "it through $HELPER, or write `// NO-EXPIRY-EXEMPT[yyyy-mm-dd]: <reason>` saying why this kind " +
                    "has nothing to age out",
            )
        }
    }

    private fun banProblems(rel: String, raw: String): List<String> {
        val code = KotlinText.stripComments(raw)
        val problems = mutableListOf<String>()
        if (PRIVATE_TTL.containsMatchIn(code)) {
            problems += "$rel declares its own synthetic-expiry constant — $TTL in $HELPER_FILE is the one ceiling, " +
                "and a second copy is exactly the drift the helper was created to end"
        }
        if (HAND_ROLLED.containsMatchIn(code)) {
            problems += "$rel converts a lifetime to milliseconds by hand (DR-177) — `seconds * 1000` wraps for a " +
                "hostile expires_in and the sum wraps sooner, so the credential reads expired every turn and every " +
                "turn refreshes into the same bad field. Use $HELPER.expiryFromNowMs"
        }
        return problems
    }
}

class OneMissingExpiryPolicyLawTest {
    private val map = ProjectMap.fromSystemProperties()

    @Test
    fun `every auth provider disposes of the missing-expiry policy - SH-01`() {
        val sources = KotlinText.kotlinFiles(map).associate { file -> KotlinText.rel(map, file) to file.readText() }
        val problems = OneMissingExpiryPolicy.audit(sources)
        assertTrue(problems.isEmpty()) {
            problems.joinToString(
                separator = "\n  - ",
                prefix = "ONE MISSING-EXPIRY POLICY (SH-01) violated:\n  - ",
            )
        }
    }

    @Test
    fun `the law can actually fail - an undisposed provider, a blank reason, and both at once`() {
        assertHit(OneMissingExpiryPolicy.audit(corpus(NEW to "class NewAuthProvider")), "NewAuthProvider.kt") {
            "a provider that neither consumes the helper nor claims an exemption must be RED"
        }
        assertEquals(
            emptyList<String>(),
            OneMissingExpiryPolicy.audit(corpus(NEW to "val e = CredentialExpiry.synthesizedExpiryMs(m, n)")),
            "consuming the helper is a disposition",
        )
        assertEquals(
            emptyList<String>(),
            OneMissingExpiryPolicy.audit(corpus(NEW to "// NO-EXPIRY-EXEMPT[2026-09-21]: api keys do not expire")),
            "a dated marker with a written reason is the other disposition",
        )
        assertHit(OneMissingExpiryPolicy.audit(corpus(NEW to BLANK_REASON)), "blank reason") {
            "a marker with no reason must be RED, not silently exempt"
        }
        assertHit(
            OneMissingExpiryPolicy.audit(corpus(NEW to BOTH_DISPOSITIONS)),
            "one disposition per provider",
        ) { "claiming both dispositions is a violation, because a reader cannot tell which is true" }
    }

    @Test
    fun `the law can actually fail - a comment is not consumption, and the two bans fire`() {
        assertHit(
            OneMissingExpiryPolicy.audit(corpus(NEW to COMMENT_ONLY)),
            "NewAuthProvider.kt",
            "derives no expiry",
        ) { "a comment mentioning the helper is prose, not a call — the wall it replaces could not tell them apart" }

        assertHit(
            OneMissingExpiryPolicy.audit(corpus(OTHER to "private const val SYNTHETIC_EXPIRY_TTL_MS = 900L")),
            "own synthetic-expiry constant",
        ) { "a second copy of the TTL anywhere must be RED" }
        assertEquals(
            emptyList<String>(),
            OneMissingExpiryPolicy.audit(corpus(OTHER to UNRELATED_CONSTANT)),
            "an unrelated SYNTHETIC constant is not a TTL copy",
        )

        assertHit(
            OneMissingExpiryPolicy.audit(corpus(OTHER to "val at = now + expiresIn * 1000")),
            "by hand (DR-177)",
        ) { "the hand-rolled lifetime conversion must be RED" }
        assertEquals(
            emptyList<String>(),
            OneMissingExpiryPolicy.audit(corpus(OTHER to OLD_SPELLING_IN_PROSE)),
            "the comment recording the old spelling is not the old spelling",
        )
    }

    @Test
    fun `the law refuses on an empty sweep and on a missing helper`() {
        assertHit(OneMissingExpiryPolicy.audit(emptyMap()), "empty sweep means the extractor") {
            "no provider swept at all must REFUSE, never pass"
        }
        assertHit(OneMissingExpiryPolicy.audit(mapOf(NEW to "val e = CredentialExpiry.x()")), "has no home") {
            "a covered provider graded against an absent helper must REFUSE"
        }
        assertHit(
            OneMissingExpiryPolicy.audit(
                mapOf(OneMissingExpiryPolicy.HELPER_FILE to "val ttl = 1L", NEW to "val e = CredentialExpiry.x()"),
            ),
            "no longer declares",
        ) { "a helper that stopped declaring the shared ceiling must REFUSE" }
    }

    // The corpus carries a COMPLIANT provider by default, and each case overrides it. Without one,
    // every ban-only case would trip the empty-denominator refusal instead of the ban it is testing
    // — which is the refusal doing its job, and the reason it is tested on its own below.
    private fun corpus(vararg files: Pair<String, String>): Map<String, String> =
        mapOf(
            OneMissingExpiryPolicy.HELPER_FILE to HELPER_SOURCE,
            NEW to COMPLIANT,
        ) + files.toMap()

    private companion object {
        const val NEW = "providers/new/src/main/kotlin/splice/provider/new/NewAuthProvider.kt"
        const val OTHER = "providers/new/src/main/kotlin/splice/provider/new/NewTokens.kt"
        const val HELPER_SOURCE = "public const val SYNTHETIC_EXPIRY_TTL_MS: Long = 4 * 60 * 60 * 1000L\n"
        const val COMPLIANT = "val e = CredentialExpiry.synthesizedExpiryMs(m, n)\n"
        const val BLANK_REASON = "// NO-EXPIRY-EXEMPT[2026-09-21]:   "
        const val BOTH_DISPOSITIONS =
            "// NO-EXPIRY-EXEMPT[2026-09-21]: none\nval e = CredentialExpiry.synthesizedExpiryMs(m, n)"
        const val COMMENT_ONLY = "// we use CredentialExpiry.synthesizedExpiryMs here\nval x = 1"
        const val UNRELATED_CONSTANT = "private const val SYNTHETIC_SIGNATURE = \"splice-synth-v1\""
        const val OLD_SPELLING_IN_PROSE = "// DR-177: this was nowMs + expiresIn * 1000, which wrapped"
    }
}
