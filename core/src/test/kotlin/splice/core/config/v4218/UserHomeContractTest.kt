// NEW: V4-218 — UserHome's contract, cell by cell. Every splice path resolves ~ through it, so each rule
// below is a rule of every path: HOME wins, a blank or unset HOME falls back to user.home, `~/` expands
// against the answer and nothing else does, and a test's redirect ends when its block does, thrown or not.
package splice.core.config.v4218

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import splice.core.config.UserHome
import splice.core.util.EnvReader
import java.nio.file.Path
import java.nio.file.Paths

class UserHomeContractTest {

    @Test
    fun `HOME wins over user home - V4-218`() {
        assertEquals("/home/a", UserHome.of(homeIs("/home/a"), "/home/b"))
        assertEquals(Paths.get("/home/a"), UserHome.dir(homeIs("/home/a")))
    }

    @Test
    fun `a blank or unset HOME falls back to user home - V4-218`() {
        assertEquals("/home/b", UserHome.of(homeIs(""), "/home/b"))
        assertEquals("/home/b", UserHome.of(homeIs("  "), "/home/b"))
        assertEquals("/home/b", UserHome.of(NO_ENV, "/home/b"))
        assertEquals(Paths.get(System.getProperty("user.home")), UserHome.dir(NO_ENV))
    }

    @Test
    fun `only a leading tilde slash expands, against HOME - V4-218`() {
        val env = homeIs("/home/a")
        assertEquals("/home/a/.config/splice/keys.toml", UserHome.expand("~/.config/splice/keys.toml", env))
        assertEquals("/etc/splice.toml", UserHome.expand("/etc/splice.toml", env))
        assertEquals("rel/~/x", UserHome.expand("rel/~/x", env))
        assertEquals("~other/x", UserHome.expand("~other/x", env))
    }

    @Test
    fun `within outranks HOME for its block and restores the answer after - V4-218`() {
        val env = homeIs("/home/a")
        val rig = Paths.get("/rig/one")
        val inside = UserHome.within(rig) { UserHome.dir(env) to UserHome.expand("~/k", env) }
        assertEquals(rig to "/rig/one/k", inside)
        assertEquals(Paths.get("/home/a"), UserHome.dir(env))
    }

    @Test
    fun `within restores the outer answer when its block throws, nested or not - V4-218`() {
        val env = homeIs("/home/a")
        val outer = Paths.get("/rig/outer")
        val seen = mutableListOf<Path>()
        UserHome.within(outer) {
            assertThrows(IllegalStateException::class.java) {
                UserHome.within(Paths.get("/rig/inner")) { error("the block fails") }
            }
            seen.add(UserHome.dir(env))
        }
        assertThrows(IllegalStateException::class.java) {
            UserHome.within(outer) { error("the block fails") }
        }
        seen.add(UserHome.dir(env))
        assertEquals(listOf(outer, Paths.get("/home/a")), seen)
    }
}

private val NO_ENV = EnvReader { null }

private fun homeIs(value: String): EnvReader = EnvReader { name -> if (name == "HOME") value else null }
