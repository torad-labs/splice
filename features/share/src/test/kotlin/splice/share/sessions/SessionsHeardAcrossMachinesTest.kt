// NEW: LAN share, round 16 of PR #419's review. The goal: what one machine hears of the other's sessions reaches its
// own sessions whole. A machine that answers a look has its sessions listed here, whether or not it takes this
// machine's offer of its own (it refuses one too large to read, say), since only a machine that does not answer has
// none listed. And a session that writes to the other machine is listed there as heard from when it wrote, both ways,
// also where its mirror there is made by the note itself, never as heard from only when its machine last looked: the
// review read that such a mirror carries no time; it carries the moment the note made it. Two machines in one JVM, the
// rig in TwoMachinesTest.
package splice.share.sessions

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.share.remote.RemoteSessions
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.listDirectoryEntries

// why: when the writing session's own machine last heard from it: a minute before it writes, well inside live, and
// far from the moment it writes.
private const val HEARD_AGO_MS = 60_000L

// why: sessions whose names, together, pass the 256 KiB a sharing machine reads of an offer, while each one's record
// stays under the 64 KiB Claude Code's registry reads.
private const val CROWD = 5
private const val CROWD_NAME_CHARS = 60_000

internal class SessionsHeardAcrossMachinesTest : TwoMachinesTest() {

    /** When the record named [name] in [registry] says its session was last heard from. */
    private fun updatedAt(registry: Path, name: String): Long = registry.listDirectoryEntries("*.json")
        .map { Json.parseToJsonElement(Files.readString(it)).jsonObject }
        .single { it["name"]?.jsonPrimitive?.content == name }
        .getValue("updatedAt").jsonPrimitive.long

    /** mini's link, whose next look is past the test's wait: after its first, what crosses comes through notes. */
    private fun slowLink() {
        checkNotNull(RemoteSessions(links(joined().second), mini.mirrors, mini.acks, { }, SLOW_LOOK_MS).start(scope))
    }

    @Test
    fun `a machine that refuses this machine's offer still has its sessions listed here`() {
        torad.session("rig-lead")
        val crowd = List(CROWD) { mini.session("${"x".repeat(CROWD_NAME_CHARS)}-$it") }

        join()

        val shown = listed(crowd.first()) { "$toradName/rig-lead" in it }
        assertTrue("$toradName/rig-lead" in shown, "torad answers each look, so its sessions are listed: $shown")
    }

    @Test
    fun `a session that writes to this machine is listed here as heard from when it wrote`() {
        val rig = torad.session("rig-lead", heardAt = System.currentTimeMillis() - HEARD_AGO_MS)
        val splice = mini.session("splice")
        slowLink()
        listed(splice) { "$toradName/rig-lead" in it }
        listed(rig) { "$MINI/splice" in it }
        mini.mirrors.withdraw(toradName)
        listed(splice) { "$toradName/rig-lead" !in it }

        val wrote = System.currentTimeMillis()
        rig.send("$MINI/splice", "heard just now")

        checkNotNull(splice.heard(SETTLE_MS / 1_000)) { "mini's session took the note" }
        val heard = updatedAt(mini.registry, "$toradName/rig-lead")
        assertTrue(heard in wrote..System.currentTimeMillis(), "heard from when it wrote, not a minute before: $heard")
    }

    @Test
    fun `a session writing to the machine this one joined is listed there as heard from when it wrote`() {
        val rig = torad.session("rig-lead")
        val splice = mini.session("splice", heardAt = System.currentTimeMillis() - HEARD_AGO_MS)
        slowLink()
        listed(splice) { "$toradName/rig-lead" in it }
        listed(rig) { "$MINI/splice" in it }
        torad.mirrors.withdraw(MINI)
        listed(rig) { "$MINI/splice" !in it }

        val wrote = System.currentTimeMillis()
        splice.send("$toradName/rig-lead", "heard just now")

        checkNotNull(rig.heard(SETTLE_MS / 1_000)) { "torad's session took the note" }
        val heard = updatedAt(torad.registry, "$MINI/splice")
        assertTrue(heard in wrote..System.currentTimeMillis(), "heard from when it wrote, not a minute before: $heard")
    }
}
