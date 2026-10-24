// NEW: LAN share, round 17 of PR #419's review. The goal: a port the Sharing page saves is one sharing can open on,
// whether sharing is on or off when it is saved. One outside 1-65535, or one a command or the console already listens
// on, is refused as it is saved, in words naming it, and the file is left byte-identical; so turning sharing on later
// is never refused for a port the page took without a word. The ports a person can type are any number, so the
// property runs over generated runs of saves through the real control server, with sharing off throughout, and
// sharing is turned on at the end.
package splice.app.control

import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.config.Knob
import splice.core.testing.TestPorts
import splice.topology.TopologyLoader
import java.nio.file.Files
import kotlin.random.Random

// why: a fixed seed, so a failing run of saves is the same run next time.
private const val PORTS_SEED = 17

// why: saves in the run: enough that each kind of port comes up several times, and a good one follows a bad one.
private const val PORTS_SAVED = 24

internal class SharePortSavedTest : SharingRoutesFixture() {

    @Test
    fun `a port saved while sharing is off is one sharing can turn on at, and any other is refused in words`() {
        val taken = listOf(3901, 3902, 3903, Knob.CONTROL_PORT.count().toInt())
        val outside = listOf(0, -1, 65_536, 70_000, Int.MAX_VALUE, Int.MIN_VALUE)
        val random = Random(PORTS_SEED)
        serve { call ->
            repeat(PORTS_SAVED) { save ->
                val port = when (random.nextInt(3)) {
                    0 -> taken[random.nextInt(taken.size)]
                    1 -> outside[random.nextInt(outside.size)]
                    else -> TestPorts.reserve()
                }
                val before = Files.readString(file)
                val at = "save $save, port $port"

                val reply = put(call, "{\"port\":$port}")

                val opens = port in 1..65_535 && port !in taken
                assertEquals(opens, reply.getValue("ok").jsonPrimitive.boolean, "$at: $reply")
                if (opens) {
                    assertEquals(port, TopologyLoader.parse(Files.readString(file)).share.port, "$at: saved")
                } else {
                    assertEquals(before, Files.readString(file), "$at: the file is left byte-identical")
                    val said = reply.getValue("said").jsonArray.map { it.jsonObject }
                        .filter { it.getValue("bad").jsonPrimitive.boolean }
                        .map { it.getValue("text").jsonPrimitive.content }
                    assertTrue(said.any { "$port" in it }, "$at: the refusal names the port: $said")
                }
            }

            val on = put(call, "{\"enabled\":true}")
            assertTrue(on.getValue("ok").jsonPrimitive.boolean, "sharing turns on at the port the page saved: $on")
            put(call, "{\"enabled\":false}")
        }
    }
}
