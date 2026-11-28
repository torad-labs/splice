package splice.launch.install

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.config.UserHome
import splice.core.util.EnvReader
import java.nio.file.Files
import java.nio.file.Path

class GeminiShimTest {
    @Test
    fun `the shim runs the pinned translator then passes the same model and door credential to Claude without it`(
        @TempDir home: Path,
    ) = UserHome.within(home) {
        val share = home.resolve("share's files")
        val pipe = share.resolve("seatd/fixture/seatd-pipe")
        val bin = home.resolve("bin")
        val claude = bin.resolve("claude")
        val vars = mapOf(
            "SPLICE_BIN_DIR" to bin.toString(),
            "SPLICE_SHARE_DIR" to share.toString(),
            "HOME" to home.toString(),
        )
        val env = EnvReader { vars[it] }
        val shim = GeminiShim(env, pipe)
        for (prog in listOf(pipe, claude)) {
            Files.createDirectories(prog.parent)
            val stub = "#!/bin/sh\nprintf '%s\\n' \"\$@\" > argv\nprintf '%s' \"\$ANTHROPIC_AUTH_TOKEN\" > credential\n"
            Files.writeString(prog, stub)
            check(prog.toFile().setExecutable(true))
        }
        assertFalse(shim.current())
        assertEquals(GeminiShimLaid.Written(shim.path()), shim.lay())
        assertFalse(Files.readString(shim.path()).contains("desktop acp"))
        val words = listOf("--model", "claude-antigravity--test", "one word pair")
        val withBin = "$bin:/usr/bin:/bin"
        for ((path, installed) in listOf(withBin to true, withBin to false, "/usr/bin:/bin" to false)) {
            if (!installed) Files.deleteIfExists(pipe)
            val child = ProcessBuilder(listOf(shim.path().toString()) + words).directory(home.toFile())
                .redirectErrorStream(true).apply {
                    environment().clear()
                    environment().putAll(vars)
                    environment()["ANTHROPIC_AUTH_TOKEN"] = "test-door"
                    environment()["PATH"] = "${shim.path().parent}:$path"
                }.start()
            assertEquals("", child.inputStream.bufferedReader().readText())
            assertEquals(0, child.waitFor())
            val expected = if (installed) listOf("--acp") + words else words
            assertEquals(expected, Files.readAllLines(home.resolve("argv")))
            assertEquals("test-door", Files.readString(home.resolve("credential")))
        }
        assertTrue(shim.current())
        assertEquals(GeminiShimLaid.Current, shim.lay())
        val next = GeminiShim(env, share.resolve("seatd/next/seatd-pipe"))
        assertFalse(next.current(), "upgrade must replace the old version path")
        assertEquals(GeminiShimLaid.Written(shim.path()), next.lay())
        assertFalse(shim.current())
    }
}
