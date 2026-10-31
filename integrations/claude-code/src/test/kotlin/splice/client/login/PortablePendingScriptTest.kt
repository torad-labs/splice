package splice.client.login

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** Runs the Darwin branch under /bin/bash, including stock Bash 3.2 on the macOS runner. */
class PortablePendingScriptTest(@param:TempDir private val tmp: Path) {
    @Test
    fun `portable outcomes refuse failures and preserve the restart decision`() {
        val bin = Files.createDirectory(tmp.resolve("bin"))
        executable(bin.resolve("uname"), "#!/bin/bash\nprintf Darwin")
        val java = bin.resolve("java")
        val block = LoginHookPending.cancelBlock(
            listOf("fixture'head", "fixture"),
            "printf 'stuck\\n'",
            "printf 'foreign\\n'",
        )
        assertFalse(block.contains("mapfile"))
        val script = Files.writeString(tmp.resolve("pending.sh"), block + "\nprintf 'result=%s\\n' \"\$restarted\"\n")
        assertEquals(0, ProcessBuilder("/bin/bash", "-n", script.toString()).start().waitFor())
        for ((outcome, expected) in mapOf(
            "Clear" to "result=\n",
            "Restarted" to "result=1\n",
            "Foreign" to "foreign\n",
            "Stuck" to "stuck\n",
            "unexpected" to "stuck\n",
        )) {
            executable(java, "#!/bin/bash\nprintf '%s\\n' '$outcome'\n")
            val process = ProcessBuilder("/bin/bash", script.toString())
            process.environment()["PATH"] = "$bin:/usr/bin:/bin"
            process.environment().remove("SPLICE_JAVA")
            process.environment()["SPLICE_JAR"] = tmp.resolve("space jar.jar").toString()
            val child = process.start()
            val output = child.inputStream.readAllBytes().decodeToString()
            val errors = child.errorStream.readAllBytes().decodeToString()
            child.waitFor(10, TimeUnit.SECONDS)
            assertEquals(0, child.exitValue(), errors)
            assertEquals(expected, output, outcome)
        }
        executable(java, "#!/bin/bash\nexit 1\n")
        val failed = ProcessBuilder("/bin/bash", script.toString())
        failed.environment()["PATH"] = "$bin:/usr/bin:/bin"
        failed.environment().remove("SPLICE_JAVA")
        val child = failed.start()
        assertEquals("stuck\n", child.inputStream.readAllBytes().decodeToString())
        assertEquals(0, child.waitFor())
    }

    /** The helper is splice's own CLI, so it runs on the java the launcher runs splice on: SPLICE_JAVA when it names
     *  one, for a Mac whose java on PATH is older than 21. And it is told the jar to match before the heads, as
     *  `pending-login <jar> <head>...` reads them: without it the first head was taken for the jar, and no waiting
     *  sign-in was ever found. */
    @Test
    fun `the helper runs on the launcher's java and is told the jar it matches before the heads`() {
        val bin = Files.createDirectory(tmp.resolve("bin"))
        executable(bin.resolve("uname"), "#!/bin/bash\nprintf Darwin")
        // A java 17 on PATH refuses a jar built for 21 and exits 1.
        executable(bin.resolve("java"), "#!/bin/bash\nexit 1\n")
        val argv = tmp.resolve("argv.txt")
        val spliceJava = tmp.resolve("splice-java")
        executable(spliceJava, "#!/bin/bash\nprintf '%s\\n' \"\$@\" > '$argv'\nprintf 'Restarted\\n'\n")
        val block = LoginHookPending.cancelBlock(
            listOf("fixture'head", "fixture"),
            "printf 'stuck\\n'",
            "printf 'foreign\\n'",
        )
        val script = Files.writeString(tmp.resolve("pending.sh"), block + "\nprintf 'result=%s\\n' \"\$restarted\"\n")
        val jar = tmp.resolve("space jar.jar").toString()
        val process = ProcessBuilder("/bin/bash", script.toString())
        process.environment()["PATH"] = "$bin:/usr/bin:/bin"
        process.environment()["SPLICE_JAVA"] = spliceJava.toString()
        process.environment()["SPLICE_JAR"] = jar
        val child = process.start()
        assertEquals("result=1\n", child.inputStream.readAllBytes().decodeToString())
        assertEquals(0, child.waitFor())
        assertEquals(listOf("-jar", jar, "pending-login", jar, "fixture'head", "fixture"), Files.readAllLines(argv))
    }

    private fun executable(file: Path, body: String) {
        Files.writeString(file, body)
        check(file.toFile().setExecutable(true))
    }
}
