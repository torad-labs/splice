package splice.app.auth.claude

import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.accounts.claude.ClaudeAccountIdentity
import splice.accounts.claude.ClaudeLoginPlaceId
import splice.client.ClaudeHead
import splice.client.ClaudeLoginTarget
import splice.core.auth.CredentialKey
import splice.core.config.StatePaths
import splice.core.util.WallClock
import java.nio.file.Files
import java.nio.file.Path

private const val HEAD = "synthetic-client"
private const val NO_PLACE =
    "[claude] carrying login unreported: newest sent credential has no unambiguous login match\n"

class ClaudeCarryingPlacesTest {
    @TempDir
    lateinit var home: Path

    private val paths by lazy { StatePaths(baseOverride = home.resolve("state")) }
    private val lines = mutableListOf<String>()

    private fun location(): ClaudeLoginLocation = ClaudeLoginLocation(
        ClaudeLoginPlaceId.NATIVE,
        ClaudeLoginTarget(
            ClaudeHead(HEAD, Files.createDirectories(home.resolve("native"))),
            home.resolve("native-account.json"),
        ),
        home.resolve("native-copies"),
    )

    private fun key(token: String): String =
        requireNotNull(CredentialKey.fromHeaders(mapOf("Authorization" to "Bearer $token")))

    private fun write(directory: Path, token: String, account: String) {
        Files.createDirectories(directory)
        Files.writeString(
            directory.resolve(".credentials.json"),
            """{"claudeAiOauth":{"accessToken":"$token","expiresAt":4102444800000}}""",
        )
        Files.writeString(directory.resolve(".claude.json"), """{"oauthAccount":{"accountUuid":"copied-stale"}}""")
        ClaudeCredentialProfiles(paths.stateDir, {}).observed(key(token), ClaudeAccountIdentity(account, null))
    }

    private fun carrying(location: ClaudeLoginLocation): ClaudeCarryingPlaces = ClaudeCarryingPlaces(
        listOf(location),
        ClaudeLoginRead(paths, { lines.add(it) }, WallClock { 1000 }),
    )

    @Test
    fun `a session that stays on its native login keeps its place without a missing-place diagnostic`() {
        val native = location()
        write(native.target.head.configDir, "synthetic-native", "native-account")
        val carried = carrying(native)
        val credential = key("synthetic-native")
        carried.sent(HEAD, "staying", credential)
        carried.sent(HEAD, "staying", credential)
        assertEquals(ClaudeLoginPlaceId.NATIVE, carried.carrying(HEAD, "staying"))
        assertEquals(ClaudeLoginPlaceId.NATIVE, carried.carrying(HEAD))
        assertEquals(emptyList<String>(), lines)
    }

    @Test
    fun `a single unknown send clears its session and the next matched send restores the place`() {
        val native = location()
        write(native.target.head.configDir, "synthetic-native", "native-account")
        val carried = carrying(native)
        val nativeKey = key("synthetic-native")
        carried.sent(HEAD, "moving", nativeKey)
        carried.sent(HEAD, "staying", nativeKey)

        carried.sent(HEAD, "moving", key("synthetic-unknown"))

        assertNull(carried.carrying(HEAD, "moving"))
        assertEquals(listOf(NO_PLACE), lines)
        assertEquals(ClaudeLoginPlaceId.NATIVE, carried.carrying(HEAD, "staying"))
        carried.sent(HEAD, "moving", nativeKey)
        assertEquals(ClaudeLoginPlaceId.NATIVE, carried.carrying(HEAD, "moving"))
        assertEquals(ClaudeLoginPlaceId.NATIVE, carried.carrying(HEAD))
    }

    @Test
    fun `an unmatched credential is checked again when it later becomes a native login`() {
        val native = location()
        val carried = carrying(native)
        val credential = key("synthetic-later-native")
        carried.sent(HEAD, "moving", credential)
        carried.sent(HEAD, "moving", credential)
        assertNull(carried.carrying(HEAD, "moving"))
        assertEquals(listOf(NO_PLACE), lines, "repeated absence does not spam the diagnostic")

        write(native.target.head.configDir, "synthetic-later-native", "native-account")
        carried.sent(HEAD, "moving", credential)

        assertEquals(ClaudeLoginPlaceId.NATIVE, carried.carrying(HEAD, "moving"))
        assertEquals(listOf(NO_PLACE), lines)
    }

    @Test
    fun `an unnamed unmatched send clears only the head and never another session`() {
        val native = location()
        write(native.target.head.configDir, "synthetic-native", "native-account")
        val carried = carrying(native)
        carried.sent(HEAD, "staying", key("synthetic-native"))
        carried.sent(HEAD, null, key("synthetic-unknown"))
        assertNull(carried.carrying(HEAD))
        assertEquals(ClaudeLoginPlaceId.NATIVE, carried.carrying(HEAD, "staying"))
        assertEquals(listOf(NO_PLACE), lines)
    }

    @Test
    fun `an added login on another head never names this head's unmatched send`() {
        val native = location()
        val folders = ClaudeAccountFolders(paths.stateDir, now = WallClock { 1000 })
        val pending = folders.pending("synthetic-other-head", "foreign")
        write(pending.directory, "synthetic-foreign", "foreign-account")
        assertEquals(ClaudeAccountLanding.Added("foreign"), folders.land(pending))
        val carried = carrying(native)
        val credential = key("synthetic-foreign")
        carried.sent("synthetic-other-head", "shared-session", credential)
        carried.sent(HEAD, "shared-session", credential)
        assertEquals("foreign", carried.account("synthetic-other-head", "shared-session"))
        assertNull(carried.account(HEAD, "shared-session"))
        assertEquals(listOf(NO_PLACE), lines)
    }

    @Test
    fun `a session switching from a native login to an added pool account reports no previous place`() {
        val native = location()
        write(native.target.head.configDir, "synthetic-native", "native-account")
        val folders = ClaudeAccountFolders(paths.stateDir, now = WallClock { 1000 })
        val added = folders.pending(HEAD, "added")
        write(added.directory, "synthetic-added", "added-account")
        assertEquals(ClaudeAccountLanding.Added("added"), folders.land(added))
        assertEquals(listOf("added"), folders.accounts(HEAD).map { it.label })
        val carried = carrying(native)
        carried.sent(HEAD, "moving", key("synthetic-native"))
        carried.sent(HEAD, "staying", key("synthetic-native"))
        assertEquals(ClaudeLoginPlaceId.NATIVE, carried.carrying(HEAD, "moving"))

        val addedKey = key(requireNotNull(folders.token(HEAD, "added")))
        carried.sent(HEAD, "moving", addedKey)

        assertAll(
            { assertNull(carried.carrying(HEAD, "moving"), "an added account cannot inherit the last native place") },
            { assertNull(carried.carrying(HEAD), "the newest head send has no native place either") },
            { assertEquals(ClaudeLoginPlaceId.NATIVE, carried.carrying(HEAD, "staying")) },
            { assertEquals("added", carried.account(HEAD, "moving")) },
            { assertEquals("claude", carried.account(HEAD, "staying")) },
            { assertEquals(emptyList<String>(), lines, "a verified added account is a known login") },
            { assertFalse(lines.joinToString("").contains(addedKey)) },
            { assertFalse(lines.joinToString("").contains("synthetic-added")) },
            { assertFalse(lines.joinToString("").contains("moving")) },
        )
    }
}
