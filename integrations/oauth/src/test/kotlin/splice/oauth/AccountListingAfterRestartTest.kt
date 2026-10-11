// NEW: V4-405 — a plan account whose credential is gone stays listed after a restart. discover() kept only
// regular, non-link credential files, so an orphaned `<label>-quota.json` (the credential deleted or lost) and
// a symlinked `<label>.json` both vanished from the account list: Needs you never offered to renew, and
// V4-357's renew path was unreachable after any restart. An orphan now lists with credentialPresent false, and
// a link lists as refused with its reason, on a path no consumer can follow to a credential.
package splice.oauth

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.topology.AuthKind
import splice.core.util.LogSink
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

class AccountListingAfterRestartTest {
    private val kind = AuthKind.ChatgptOAuth
    private val silent = LogSink {}

    private fun setup(home: Path): Pair<Path, Path> {
        val primary = home.resolve("primary.json")
        Files.writeString(primary, "{}")
        return primary to Files.createDirectories(OAuthAccountFiles().poolDir(kind, primary))
    }

    private fun credential(label: String, wire: String = kind.wire): String =
        """{"splice_auth_kind":"$wire","splice_account_label":"$label","access_token":"synthetic-$label"}"""

    private fun names(dir: Path): List<String> =
        Files.list(dir).use { paths -> paths.map { it.fileName.toString() }.sorted().toList() }

    @Test
    fun `an orphaned quota lists its label with no credential after a fresh discover`(@TempDir home: Path) {
        val (primary, pool) = setup(home)
        Files.writeString(pool.resolve("work-quota.json"), "old-account-usage")

        val listed = OAuthAccountFiles().discover(kind, primary, silent)

        assertEquals(listOf("primary", "work"), listed.map(OAuthAccountFile::label))
        val work = listed.single { it.label == "work" }
        assertFalse(work.credentialPresent)
        assertFalse(work.primary)
        assertEquals(null, work.refusal)
        assertEquals(pool.resolve("work.json"), work.credentialFile)
        assertEquals(pool.resolve("work-quota.json"), work.quotaFile)
        assertFalse(Files.exists(work.credentialFile, LinkOption.NOFOLLOW_LINKS))
        assertEquals(listOf("work-quota.json"), names(pool), "listing an orphan writes and moves nothing")
        assertEquals("old-account-usage", Files.readString(work.quotaFile))
    }

    @Test
    fun `an orphan lists beside a live credential in the writer's order`(@TempDir home: Path) {
        val (primary, pool) = setup(home)
        Files.writeString(pool.resolve("plus-a.json"), credential("plus-a"))
        Files.writeString(pool.resolve("plus-a-quota.json"), "{}")
        Files.writeString(pool.resolve("work-quota.json"), "{}")

        val listed = OAuthAccountFiles().discover(kind, primary, silent)

        assertEquals(listOf("primary", "plus-a", "work"), listed.map(OAuthAccountFile::label))
        assertEquals(listOf(true, true, false), listed.map(OAuthAccountFile::credentialPresent))
        assertEquals(listOf(null, null, null), listed.map(OAuthAccountFile::refusal))
    }

    @Test
    fun `regular labeled credentials and the primary list exactly as before`(@TempDir home: Path) {
        val (primary, pool) = setup(home)
        Files.writeString(pool.resolve("plus-a.json"), credential("plus-a"))
        Files.writeString(pool.resolve("plus-a-quota.json"), "{}")
        Files.writeString(pool.resolve("plus-b.json"), credential("plus-b"))

        val listed = OAuthAccountFiles().discover(kind, primary, silent)

        assertEquals(
            listOf(
                OAuthAccountFile("primary", primary, pool.resolve("primary-quota.json"), true, true),
                OAuthAccountFile("plus-a", pool.resolve("plus-a.json"), pool.resolve("plus-a-quota.json"), false, true),
                OAuthAccountFile("plus-b", pool.resolve("plus-b.json"), pool.resolve("plus-b-quota.json"), false, true),
            ),
            listed,
        )
    }

    @Test
    fun `accounts keep the order of their credential file names`(@TempDir home: Path) {
        val (primary, pool) = setup(home)
        Files.writeString(pool.resolve("plus-a.json"), credential("plus-a"))
        Files.writeString(pool.resolve("plus-a-2.json"), credential("plus-a-2"))
        Files.writeString(pool.resolve("plus-a-quota.json"), "{}")

        val listed = OAuthAccountFiles().discover(kind, primary, silent)

        // "plus-a-2.json" sorts before "plus-a.json" ('-' precedes '.'), as it did before orphans listed.
        assertEquals(listOf("primary", "plus-a-2", "plus-a"), listed.map(OAuthAccountFile::label))
    }

    @Test
    fun `a missing primary and a missing pool still list the primary alone`(@TempDir home: Path) {
        val primary = home.resolve("primary.json")

        val listed = OAuthAccountFiles().discover(kind, primary, silent)

        assertEquals(1, listed.size)
        assertTrue(listed.single().primary)
        assertFalse(listed.single().credentialPresent)
    }

    @Test
    fun `a symlinked credential lists as refused and is never loaded`(@TempDir home: Path) {
        val (primary, pool) = setup(home)
        val target = home.resolve("someone-elses-credential")
        // A wrong kind and a foreign label: had discovery loaded it, validation would have thrown.
        Files.writeString(target, credential("someone-else", wire = "grok-oauth"))
        val linked = pool.resolve("work.json")
        Files.createSymbolicLink(linked, target)
        val log = StringBuilder()

        val listed = OAuthAccountFiles().discover(kind, primary, LogSink { log.append(it) })

        val work = listed.single { it.label == "work" }
        assertFalse(work.credentialPresent)
        val reason = requireNotNull(work.refusal)
        assertTrue(reason.contains("symbolic link"), reason)
        assertTrue(reason.contains("'work'"), reason)
        assertFalse(reason.contains(target.toString()) || reason.contains(home.toString()), reason)
        assertNotEquals(linked, work.credentialFile, "a refused link must not hand a consumer a path it could open")
        assertFalse(Files.exists(work.credentialFile, LinkOption.NOFOLLOW_LINKS))
        assertEquals(pool.resolve("work-quota.json"), work.quotaFile)
        assertEquals("", log.toString(), "a link is refused, not read, so nothing is logged from its content")
        assertTrue(Files.isSymbolicLink(linked))
        assertEquals(credential("someone-else", wire = "grok-oauth"), Files.readString(target))
    }

    @Test
    fun `a dangling link and a link with retained usage each list once`(@TempDir home: Path) {
        val (primary, pool) = setup(home)
        Files.createSymbolicLink(pool.resolve("gone.json"), home.resolve("no-such-target"))
        Files.createSymbolicLink(pool.resolve("work.json"), home.resolve("no-such-target"))
        Files.writeString(pool.resolve("work-quota.json"), "{}")

        val listed = OAuthAccountFiles().discover(kind, primary, silent)

        assertEquals(listOf("primary", "gone", "work"), listed.map(OAuthAccountFile::label))
        assertTrue(listed.drop(1).all { it.refusal != null && !it.credentialPresent })
    }

    @Test
    fun `names that cannot be an account label list nothing and do not stop discovery`(@TempDir home: Path) {
        val (primary, pool) = setup(home)
        listOf("primary-quota.json", "auto-quota.json", "x-quota-quota.json", "bad label-quota.json", "-quota.json")
            .forEach { Files.writeString(pool.resolve(it), "{}") }
        Files.createSymbolicLink(pool.resolve("bad label.json"), home.resolve("no-such-target"))
        Files.createDirectory(pool.resolve("folder.json"))
        val log = StringBuilder()

        val listed = OAuthAccountFiles().discover(kind, primary, LogSink { log.append(it) })

        assertEquals(listOf("primary"), listed.map(OAuthAccountFile::label))
        assertEquals("", log.toString())
    }
}
