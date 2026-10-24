// NEW: LAN share, round 17 of PR #419's review. The goal: another machine's session is listed here once, whichever ways
// it reaches this machine, for as long as any of those ways still names it, and under a machine that does: the same
// mirror under the same name while the machine it is listed under still names it. Two machines that each joined the
// other reach each other two ways: this machine's own look at the other, and the other's offers here. One way going
// quiet (a look that fails, a revoke, its offers ending) leaves the other's mirrors as they are; a machine this one
// keys twice, `b` and `b-2`, lists a session it names under both keys once. And a message to a mirror is carried by a
// way that names its session, so it reaches a machine where that session runs. Ways claim and let go in any order, so
// the property runs over generated runs of looks, offers and withdrawals, with which machine stands behind each way
// drawn per run: one machine reached three ways, or two that go by one name. Read from the Claude Code registry the
// mirrors write, through a session here that writes to them.
package splice.share.sessions

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.sessions.mirror.MirrorCarried
import splice.sessions.mirror.MirrorCarrier
import splice.sessions.mirror.MirrorLink
import splice.sessions.mirror.MirrorWay
import splice.sessions.mirror.MirroredSession
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.io.path.listDirectoryEntries
import kotlin.random.Random

// why: a fixed seed, so a failing run of claims is the same run next time.
private const val WAYS_SEED = 17

// why: runs, and steps in each: enough that every way claims, lets go and claims again while the others hold.
private const val WAYS_RUNS = 6
private const val WAYS_STEPS = 14

/** One way a machine's sessions reach this one: the key it files them under here, and which way. */
private data class Way(val machine: String, val way: MirrorWay)

// why: the three ways two machines that each joined this one under one name, one of them twice, can reach it.
private val WAYS = listOf(Way("b", MirrorWay.LOOK), Way("b", MirrorWay.OFFER), Way("b-2", MirrorWay.LOOK))

/** A mirror as the registry lists it: its name, its socket, and the Claude Code session id it carries. */
private data class Listed(val name: String, val socket: String, val record: String)

// why: the kind a mirror's registry record names, which no session Claude Code starts carries.
private const val MIRROR = "splice-mirror"

internal class MirrorWaysTest : TwoMachinesTest() {

    /** Two machines' running sessions by id, with their names: two of the first go by one name, as two sessions
     *  started in one folder do, and one of each machine's goes by a name the other's has too. */
    private val machines = listOf(
        listOf("work", "rig", "work").associateBy { UUID.randomUUID().toString() },
        listOf("work", "review").associateBy { UUID.randomUUID().toString() },
    )

    /** The mirrors torad lists now. */
    private fun listed(): List<Listed> = torad.registry.listDirectoryEntries("*.json").mapNotNull { file ->
        val record = Json.parseToJsonElement(Files.readString(file)).jsonObject
        fun field(key: String) = record[key]?.jsonPrimitive?.content
        if (field("kind") != MIRROR) return@mapNotNull null
        val name = field("name") ?: return@mapNotNull null
        Listed(name, field("messagingSocketPath").orEmpty(), field("sessionId").orEmpty())
    }

    /** The session a listed mirror stands for: the id whose record either key would give it. */
    private fun idOf(listed: Listed): String? = machines.flatMap { it.keys }.firstOrNull { id ->
        WAYS.any { UUID.nameUUIDFromBytes("${it.machine}/$id".toByteArray()).toString() == listed.record }
    }

    @Test
    fun `a session is listed once while any way names it, keeps its mirror, and is carried where it runs`() {
        val writer = torad.session("writer")
        val random = Random(WAYS_SEED)
        repeat(WAYS_RUNS) { run ->
            WaysRun(random, writer).steps(run)
            WAYS.forEach { torad.mirrors.withdraw(it.machine, it.way) }
            assertEquals(emptyList<Listed>(), listed(), "run $run: no way names anything, so nothing is listed")
        }
    }

    /** One generated run: which machine stands behind each way (the first behind `b`'s look, either behind the
     *  others), what each way names now, and what was listed after the step before. */
    private inner class WaysRun(private val random: Random, private val writer: ClaudeCodeSession) {
        private val behind = WAYS.associateWith { if (it == WAYS.first()) 0 else random.nextInt(machines.size) }
        private val carried = ConcurrentLinkedQueue<Pair<Int, String>>()
        private val links = behind.mapValues { (way, machine) ->
            val carrier = MirrorCarrier { to, _, _ -> MirrorCarried(null).also { carried += machine to to.id } }
            MirrorLink(carrier, HOUR_MS, way.way)
        }
        private val claims = mutableMapOf<Way, Set<String>>()
        private var before = emptyMap<String, Listed>()

        fun steps(run: Int) {
            repeat(WAYS_STEPS) { step ->
                val way = WAYS[random.nextInt(WAYS.size)]
                claim(way)
                val at = "run $run, step $step, ${way.machine} ${way.way}, behind $behind, claims $claims"
                before = checked(at)
                sent(at, before)
            }
        }

        /** A step on [way]: it goes quiet one time in four, else names a random part of its machine's sessions. */
        private fun claim(way: Way) {
            if (random.nextInt(4) == 0) {
                torad.mirrors.withdraw(way.machine, way.way)
                claims -= way
                return
            }
            val running = machines[behind.getValue(way)].filter { random.nextInt(4) > 0 }
            val sessions = running.map { (id, name) -> MirroredSession(id, name) }
            torad.mirrors.mirror(way.machine, sessions, links.getValue(way))
            claims[way] = running.keys
        }

        /** Whether [machine] names [id] by some way now. */
        private fun names(machine: String, id: String): Boolean =
            claims.any { (w, ids) -> w.machine == machine && id in ids }

        /** The listing holds the properties after a step; answers it by session id. */
        private fun checked(at: String): Map<String, Listed> {
            val now = listed()
            val byId = now.groupBy { idOf(it) }
            val named = claims.values.flatten().toSet()
            assertEquals(named, byId.keys, "$at: each session a way names is listed, and none other: $now")
            assertTrue(byId.values.all { it.size == 1 }, "$at: each once: $now")
            assertEquals(now.size, now.map { it.name }.toSet().size, "$at: no two listed alike: $now")
            val kept = byId.mapNotNull { (id, mirrors) -> id?.let { it to mirrors.single() } }.toMap()
            val stays = before.filter { (id, was) -> id in kept && names(was.name.substringBefore("/"), id) }
            stays.forEach { (id, was) ->
                assertEquals(was, kept[id], "$at: a session its machine still names keeps its mirror")
            }
            kept.forEach { (id, mirror) ->
                assertTrue(names(mirror.name.substringBefore("/"), id), "$at: $mirror under one naming it")
            }
            return kept
        }

        /** A message to one listed mirror at random is carried to a machine where its session runs. */
        private fun sent(at: String, kept: Map<String, Listed>) {
            val picked = kept.entries.elementAtOrNull(random.nextInt(kept.size.coerceAtLeast(1))) ?: return
            val (id, mirror) = picked.toPair()
            carried.clear()
            writer.send(mirror.name, "to $id")
            val took = runBlocking {
                val until = System.currentTimeMillis() + SETTLE_MS
                while (carried.isEmpty() && System.currentTimeMillis() < until) delay(POLL_MS)
                carried.firstOrNull()
            }
            val runsOn = machines.indices.filter { id in machines[it] }
            assertTrue(took != null && took.second == id && took.first in runsOn, "$at: $id carried as $took")
        }
    }
}
