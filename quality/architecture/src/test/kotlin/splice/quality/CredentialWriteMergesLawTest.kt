// NEW: SH-10's structural half — a provider that writes a credential file MERGES onto what is
// already there (ported from the sh_10_kimi/sh_10_muse wall pair, restructure PR 6 §2.7).
//
// WHY THIS EXISTS. KimiAuthProvider used to write a fixed six-key object over whatever was on disk,
// dropping every field kimi-cli and kimi-code store beside ours. The fix was a shared primitive —
// CredentialJson.mergedCredentialJson — so that "merge, never rewrite" is a property of the write
// rather than a habit three files independently remember and one already forgot.
//
// WHAT ACTUALLY PROVES IT is behavioural and stays where it is: KimiAuthProviderTest `refresh
// merges onto the on-disk file - foreign fields survive rotation - SH-10`, MuseAuthProviderTest
// `refresh mints in refresh mode and merges metadata without dropping credential fields`,
// MuseAuthProviderFixesTest `unknown mint-body fields drop while on-disk unknowns survive`,
// CodexAuthTest `single-flight refresh preserves other fields, writes 0600, one refresh call`, and
// GrokAccountMetadataTest `refresh preserves labeled metadata`. Each writes a foreign field, runs a
// refresh, and reads the file back. That is strictly stronger than what the wall did: the wall
// traced the VALUE from the merge call through local variables and private forwards to the write,
// which proves the syntax is shaped right, while these prove the field is still on disk.
//
// WHAT THE TESTS CANNOT DO is notice a SECOND writer. A new file that calls the atomic write with a
// from-scratch object breaks nothing those tests exercise — they drive the provider they know
// about. The wall covered this with a hand-maintained map of every other atomic writer in the tree,
// which is the shape that goes stale the moment a file is renamed. This law inverts it: the
// denominator is every provider file that calls the write, found by sweeping, and each one must
// either merge or say in its own source why it is not a credential write.
//
// SCOPED TO providers/. The atomic write is the tree's one credential-write primitive and about
// twenty files outside providers/ use it — config.json, teams.json, the key store, the usage ring,
// Claude Code's own materializer. None of them persists a PROVIDER credential, which is what SH-10
// is about, and the wall listed them by name purely to exclude them. Excluding them by scope is the
// same exclusion with nothing to maintain. A credential write added outside providers/ is not
// covered here, exactly as it was not covered there.
package splice.quality

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal object CredentialWriteMerges {
    const val WRITE: String = "SecureFile.writeAtomic0600("
    const val PRIMITIVE_FILE: String = "core/src/main/kotlin/splice/core/auth/CredentialJson.kt"
    const val PRIMITIVE: String = "mergedCredentialJson"

    /** The shared primitive, or a provider-local merge built on the same rule (grok, codex). */
    private val MERGE = Regex("merged[A-Za-z]*Json\\s*\\(")
    private val PROVIDER_FILE = Regex("^providers/([^/]+)/src/main/.*\\.kt$")
    private val EXEMPT = Regex("//\\s*CREDENTIAL-WRITE-EXEMPT\\[(\\d{4}-\\d{2}-\\d{2})]:(.*)")

    /** Pure: repo-relative path -> source text. */
    fun audit(sources: Map<String, String>): List<String> {
        val problems = mutableListOf<String>()
        val primitive = sources[PRIMITIVE_FILE]
        if (primitive == null || !primitive.contains("fun $PRIMITIVE")) {
            problems += "$PRIMITIVE_FILE no longer declares $PRIMITIVE — merge-never-rewrite is a property of " +
                "the shared primitive, and without it every provider below is graded against nothing"
        }
        val writers = sources.filterKeys { PROVIDER_FILE.matches(it) }
            .filterValues { KotlinText.stripComments(it).contains(WRITE) }
            .keys.sorted()
        if (writers.isEmpty()) {
            problems += "no file under providers/ calls $WRITE — the credential writers are this law's " +
                "denominator, so an empty sweep means the extractor or the project map broke, not that the " +
                "tree complies"
        }
        writers.forEach { rel -> problems += dispositionProblems(rel, sources.getValue(rel)) }
        return problems
    }

    private fun dispositionProblems(rel: String, raw: String): List<String> {
        val merges = MERGE.containsMatchIn(KotlinText.stripComments(raw))
        val exempt = EXEMPT.find(raw)
        val reason = exempt?.groupValues?.get(2)?.trim().orEmpty()
        return when {
            merges && exempt != null -> listOf(
                "$rel both merges and claims CREDENTIAL-WRITE-EXEMPT — one disposition per writer, or a reader " +
                    "cannot tell whether this file persists a credential",
            )
            merges -> emptyList()
            exempt != null && reason.isEmpty() -> listOf(
                "$rel carries a CREDENTIAL-WRITE-EXEMPT marker with no reason — a blank reason is an absence " +
                    "wearing a label; name the file it writes and why losing a foreign field there is not a " +
                    "credential loss",
            )
            exempt != null -> emptyList()
            else -> listOf(
                "$rel calls $WRITE without merging onto what is on disk. A from-scratch object written over a " +
                    "provider's credential file drops every field the vendor's own CLI stores beside ours — the " +
                    "2026-08-07 kimi defect. Build the object with $PRIMITIVE (or the provider's merged*Json), or " +
                    "write `// CREDENTIAL-WRITE-EXEMPT[yyyy-mm-dd]: <reason>` saying what this file writes instead",
            )
        }
    }
}

class CredentialWriteMergesLawTest {
    private val map = ProjectMap.fromSystemProperties()

    @Test
    fun `every provider credential write merges onto the file on disk - SH-10`() {
        val sources = KotlinText.kotlinFiles(map).associate { file -> KotlinText.rel(map, file) to file.readText() }
        val problems = CredentialWriteMerges.audit(sources)
        assertTrue(problems.isEmpty()) {
            problems.joinToString(separator = "\n  - ", prefix = "CREDENTIAL WRITES MERGE (SH-10) violated:\n  - ")
        }
    }

    @Test
    fun `the law can actually fail - a second writer that rewrites from scratch`() {
        assertHit(CredentialWriteMerges.audit(corpus(NEW to REWRITES)), "without merging onto what is on disk") {
            "a provider file that writes the credential file from scratch must be RED — this is the whole defect"
        }
        assertEquals(
            emptyList<String>(),
            CredentialWriteMerges.audit(corpus(NEW to MERGES)),
            "merging is a disposition",
        )
        assertEquals(
            emptyList<String>(),
            CredentialWriteMerges.audit(corpus(NEW to EXEMPTED)),
            "a dated marker with a written reason is the other disposition",
        )
        assertHit(CredentialWriteMerges.audit(corpus(NEW to BLANK)), "blank reason") {
            "a marker with no reason must be RED, not silently exempt"
        }
        assertHit(CredentialWriteMerges.audit(corpus(NEW to BOTH)), "one disposition per writer") {
            "merging AND claiming the exemption is a violation"
        }
    }

    @Test
    fun `the law can actually fail - a merge named only in a comment, and a write named only in one`() {
        assertHit(CredentialWriteMerges.audit(corpus(NEW to MERGE_IN_PROSE)), "without merging") {
            "a comment mentioning the merge is prose, not a call"
        }
        assertEquals(
            emptyList<String>(),
            CredentialWriteMerges.audit(corpus(NEW to WRITE_IN_PROSE)),
            "a comment mentioning the write does not make the file a writer — it would demand a disposition " +
                "from a file that writes nothing",
        )
    }

    @Test
    fun `the law refuses on an empty sweep and on a missing primitive`() {
        val noWriters = mapOf(CredentialWriteMerges.PRIMITIVE_FILE to PRIMITIVE, NEW to WRITE_IN_PROSE)
        assertHit(CredentialWriteMerges.audit(noWriters), "empty sweep") {
            "no provider writer swept at all must REFUSE, never pass"
        }
        assertHit(CredentialWriteMerges.audit(mapOf(NEW to MERGES)), "no longer declares") {
            "a tree whose shared primitive is gone must REFUSE"
        }
    }

    // The corpus always carries one compliant writer at a DIFFERENT path from the case under test,
    // so a case whose file turns out not to be a writer cannot empty the denominator and trip the
    // refusal instead of the rule it is grading. That refusal is exercised on its own below.
    private fun corpus(vararg files: Pair<String, String>): Map<String, String> =
        mapOf(
            CredentialWriteMerges.PRIMITIVE_FILE to PRIMITIVE,
            BASE to MERGES,
        ) + files.toMap()

    private companion object {
        const val NEW = "providers/new/src/main/kotlin/splice/provider/new/NewPersistence.kt"
        const val BASE = "providers/base/src/main/kotlin/splice/provider/base/BasePersistence.kt"
        const val PRIMITIVE = "public fun mergedCredentialJson(onDisk: JsonObject?, replacements: JsonObject)\n"
        const val REWRITES = "SecureFile.writeAtomic0600(path, newAuthJson(tokens).toString())\n"
        const val MERGES =
            "val merged = CredentialJson.mergedCredentialJson(onDisk, fresh)\nSecureFile.writeAtomic0600(p, merged)\n"
        const val EXEMPTED =
            "// CREDENTIAL-WRITE-EXEMPT[2026-09-21]: device identity, never a credential\n" +
                "SecureFile.writeAtomic0600(path, id)\n"
        const val BLANK = "// CREDENTIAL-WRITE-EXEMPT[2026-09-21]:  \nSecureFile.writeAtomic0600(path, id)\n"
        const val BOTH =
            "// CREDENTIAL-WRITE-EXEMPT[2026-09-21]: none\nval m = mergedCredentialJson(a, b)\n" +
                "SecureFile.writeAtomic0600(p, m)\n"
        const val MERGE_IN_PROSE =
            "// built with mergedCredentialJson(onDisk, fresh)\nSecureFile.writeAtomic0600(p, rebuilt)\n"
        const val WRITE_IN_PROSE = "// the result goes through SecureFile.writeAtomic0600(path, text)\nval x = 1\n"
    }
}
