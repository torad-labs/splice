// NEW: LAN share, phase 2, milestone 5 (docs/specs/lan-share.md). Reading `tailscale status --json` on this machine: the
// tailscale on the PATH, or in a place it installs to outside it, is asked, and what it prints is the status. No
// tailscale, or one that fails, is no status, never an error.
package splice.share.remote

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.util.EnvReader
import java.io.File

class TailscaleCliTest {

    @TempDir
    lateinit var dir: File

    private fun tailscale(at: File, script: String): File = at.resolve("tailscale").apply {
        parentFile.mkdirs()
        writeText("#!/bin/sh\n$script\n")
        setExecutable(true)
    }

    private fun path(vararg dirs: File) = EnvReader { name -> dirs.joinToString(":").takeIf { name == "PATH" } }

    @Test
    fun `the tailscale on the PATH is asked, and what it prints is the status`() {
        val bin = dir.resolve("bin")
        tailscale(bin, "[ \"\$*\" = \"status --json\" ] && echo '{\"Peer\": {}}'")
        assertEquals("{\"Peer\": {}}", TailscaleCli(path(bin), emptyList()).json()?.trim())
    }

    @Test
    fun `a tailscale outside the PATH, where the app installs it, is found there`() {
        val app = tailscale(dir.resolve("Tailscale.app"), "echo '{}'")
        assertEquals("{}", TailscaleCli(path(dir.resolve("empty")), listOf(app.path)).json()?.trim())
    }

    @Test
    fun `no tailscale here, or one that fails, is no status`() {
        assertNull(TailscaleCli(path(dir.resolve("empty")), emptyList()).json())
        val bin = dir.resolve("bin")
        tailscale(bin, "echo 'failed to connect to local tailscaled' >&2; exit 1")
        assertNull(TailscaleCli(path(bin), emptyList()).json())
    }
}
