package splice.client.login

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.process.LaunchOwners
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class PortablePendingLoginTest(@param:TempDir private val tmp: Path) {
    @Test
    fun `non proc identity cancels a hook login but leaves a foreign login alone`() {
        val jar = LoginProcesses.parkJar(tmp)
        val word = tmp.resolve("fixture-head")
        val owners = LaunchOwners(tmp.resolve("state"))
        val process = LoginProcesses.pendingLogin(word, jar, false)
        try {
            assertEquals(PendingLoginOutcome.Foreign, PendingLogins(owners).cancel(jar, listOf(word.toString())))
            assertTrue(process.isAlive)
            owners.write(process.pid(), word.toString(), "", "login", "hook")
            assertEquals(PendingLoginOutcome.Restarted, PendingLogins(owners).cancel(jar, listOf(word.toString())))
            assertTrue(process.waitFor(5, TimeUnit.SECONDS))
        } finally {
            process.destroyForcibly()
        }
    }

    @Test
    fun `a valid flag-first login is found before starting a duplicate`() {
        val jar = LoginProcesses.parkJar(tmp)
        val word = tmp.resolve("fixture-head").toString()
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val process = ProcessBuilder(java, "-jar", jar.toString(), "login", "--label", "work", word).start()
        val owners = LaunchOwners(tmp.resolve("state"))
        try {
            assertEquals(PendingLoginOutcome.Foreign, PendingLogins(owners).cancel(jar, listOf(word)))
            owners.write(process.pid(), word, "", "login", "hook")
            assertEquals(PendingLoginOutcome.Restarted, PendingLogins(owners).cancel(jar, listOf(word)))
            assertTrue(process.waitFor(5, TimeUnit.SECONDS))
        } finally {
            process.destroyForcibly()
        }
    }

    @Test
    fun `a hook-owned login that ignores TERM is forcefully ended`() {
        val jar = LoginProcesses.parkJar(tmp)
        val word = tmp.resolve("fixture-head")
        val owners = LaunchOwners(tmp.resolve("state"))
        val process = LoginProcesses.pendingLogin(word, jar, true)
        try {
            owners.write(process.pid(), word.toString(), "", "login", "hook")
            assertEquals(PendingLoginOutcome.Restarted, PendingLogins(owners).cancel(jar, listOf(word.toString())))
            assertTrue(process.waitFor(5, TimeUnit.SECONDS))
        } finally {
            process.destroyForcibly()
        }
    }

    @Test
    fun `a foreign login prevents cancellation of any hook-owned match`() {
        val jar = LoginProcesses.parkJar(tmp)
        val word = tmp.resolve("fixture-head")
        val owners = LaunchOwners(tmp.resolve("state"))
        val hook = LoginProcesses.pendingLogin(word, jar, false)
        val foreign = LoginProcesses.terminalLogin(word, jar)
        try {
            owners.write(hook.pid(), word.toString(), "", "login", "hook")
            assertEquals(PendingLoginOutcome.Foreign, PendingLogins(owners).cancel(jar, listOf(word.toString())))
            assertTrue(hook.isAlive)
            assertTrue(foreign.isAlive)
        } finally {
            hook.destroyForcibly()
            foreign.destroyForcibly()
        }
    }

    @Test
    fun `application arguments cannot impersonate the launchers jar invocation`() {
        val jar = LoginProcesses.parkJar(tmp)
        val word = tmp.resolve("fixture-head")
        val owners = LaunchOwners(tmp.resolve("state"))
        val unrelated = LoginProcesses.otherJvm(tmp, word, jar, true)
        try {
            owners.write(unrelated.pid(), word.toString(), "", "login", "hook")
            assertEquals(PendingLoginOutcome.Clear, PendingLogins(owners).cancel(jar, listOf(word.toString())))
            assertTrue(unrelated.isAlive)
        } finally {
            unrelated.destroyForcibly()
        }
    }
}
