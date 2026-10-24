// NEW: LAN share, round 15 of PR #419's review. The goal: a session here that writes to another machine's session by
// the name it lists keeps reaching it, and the replies keep coming back, for as long as that session runs under that
// name. Its mirror is never torn down and made again, and never renamed, because the machine's other sessions came,
// went or took the same name; and no two of one machine's sessions are listed alike. The property is over generated
// runs of one machine's offers, in which names repeat, sessions end, come back and change names, read from the Claude
// Code registry the mirrors write: each mirror's name and socket, by the session it stands for.
package splice.share.sessions

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import splice.sessions.mirror.MirrorCarried
import splice.sessions.mirror.MirrorCarrier
import splice.sessions.mirror.MirrorLink
import splice.sessions.mirror.MirroredSession
import java.nio.file.Files
import java.util.UUID
import kotlin.io.path.listDirectoryEntries
import kotlin.random.Random

// why: a fixed seed, so a failing run of offers is the same run next time.
private const val OFFERS_SEED = 15

// why: runs, and offers in each: enough that a name is taken twice, let go of and taken again while others keep theirs.
private const val OFFERS_RUNS = 6
private const val OFFERS_STEPS = 12

// why: the sessions the other machine may offer: a few, so two of them often go by one name.
private const val OFFERED_AT_MOST = 5

// why: the names they go by, one of them twice as likely, as two sessions started in one folder are named alike.
private val OFFERED_NAMES = listOf("work", "work", "rig-lead", "review")

internal class MirrorNamesTest : TwoMachinesTest() {

    /** The mirrors torad lists now, by the Claude Code session id of each: its name and its socket. */
    private fun mirrored(): Map<String, Pair<String, String>> =
        torad.registry.listDirectoryEntries("*.json").mapNotNull { file ->
            val record = Json.parseToJsonElement(Files.readString(file)).jsonObject
            val id = record["sessionId"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val name = record["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val socket = record["messagingSocketPath"]?.jsonPrimitive?.content ?: return@mapNotNull null
            id to (name to socket)
        }.toMap()

    /** The Claude Code session id of the mirror of mini's session [id], as the mirror writes it in its record. */
    private fun recordOf(id: String): String = UUID.nameUUIDFromBytes("$MINI/$id".toByteArray()).toString()

    @Test
    fun `a mirrored session keeps its name and socket while it runs under that name, whatever the others do`() {
        val random = Random(OFFERS_SEED)
        val ids = List(OFFERED_AT_MOST) { UUID(random.nextLong(), random.nextLong()).toString() }
        val link = MirrorLink(MirrorCarrier { _, _, _ -> MirrorCarried(null) }, HOUR_MS)
        repeat(OFFERS_RUNS) { run ->
            var before = emptyMap<String, String>()
            var listed = emptyMap<String, Pair<String, String>>()
            repeat(OFFERS_STEPS) { step ->
                // Most sessions run on; some end, some start, and now and then one is renamed.
                val offered = ids.filter { random.nextInt(3) > 0 }.associateWith { id ->
                    before[id]?.takeIf { random.nextInt(5) > 0 } ?: OFFERED_NAMES[random.nextInt(OFFERED_NAMES.size)]
                }

                torad.mirrors.mirror(MINI, offered.map { (id, name) -> MirroredSession(id, name) }, link)

                val now = mirrored()
                val at = "run $run, step $step"
                assertEquals(offered.keys.map(::recordOf).toSet(), now.keys, "$at: each offered session is mirrored")
                assertEquals(now.size, now.values.map { it.first }.toSet().size, "$at: no two listed alike: $now")
                offered.filter { (id, name) -> before[id] == name }.keys.forEach { id ->
                    val record = recordOf(id)
                    assertEquals(listed[record], now[record], "$at: ${offered[id]} kept its mirror: $offered")
                }
                before = offered
                listed = now
            }
            torad.mirrors.mirror(MINI, emptyList(), link)
        }
    }
}
