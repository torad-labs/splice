// NEW: V4-129 — the fixtures WrappedHead's tests share: a real filesystem rig (bin, share, versions, state) and
// the operator's real Claude state, so V4-445's update and untouched-state tests read the same rig as the
// wrap and unwrap tests.
package splice.client.wrap

import org.junit.jupiter.api.Assertions.assertEquals
import splice.core.config.InstallPaths
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** One rig: bin/, share/, a real "binary" file the pre-existing claude symlink points at, the
 *  shim file itself, and a WrappedHead wired to all of it plus a fixed clock. */
internal class WrapRig(home: Path) {
    val bin: Path = home.resolve("bin").createDirectories()
    val share: Path = home.resolve("share").createDirectories()
    val versions: Path = home.resolve("versions").createDirectories()
    val realBinary: Path = binaryAt(versions.resolve("2.1.278"))
    val cmd: Path = bin.resolve("claude")
    val shim: Path = share.resolve("splice-launch").also { it.writeText("#!/usr/bin/env bash\n") }
    val installPaths = InstallPaths(binOverride = bin, shareOverride = share)
    val stateStore = WrapStateStore(file = home.resolve("state").resolve("claude-head-wrap.json"))
    var clock = 1_000L
    val head = WrappedHead(
        home = home,
        installPaths = installPaths,
        stateStore = stateStore,
        now = { clock },
    )

    fun linkCmdToReal() = Files.createSymbolicLink(cmd, realBinary)

    private fun binaryAt(path: Path): Path = path.also {
        it.writeText("#!/bin/sh\n")
        it.toFile().setExecutable(true)
    }

    /** What Claude Code's updater does on a release: installs [version] beside the others, re-points `claude`
     *  at it in one atomic rename (replacing whatever is there, the shim included), and deletes every older
     *  version. Returns the new binary. */
    fun update(version: String): Path {
        val next = binaryAt(versions.resolve(version))
        val staged = bin.resolve(".claude.updating")
        Files.createSymbolicLink(staged, next)
        Files.move(staged, cmd, ATOMIC_MOVE, REPLACE_EXISTING)
        versions.toFile().listFiles().orEmpty().filter { it != next.toFile() }.forEach { it.delete() }
        return next
    }
}

/** The operator's real Claude state in a temp home: the ~/.claude.json Claude Code reads with no
 *  CLAUDE_CONFIG_DIR (mcpServers, projects, the account) and a ~/.claude with its own settings, hooks and
 *  transcripts. [assertUntouched] is the V4-445 contract: none of it changed, and nothing was added. */
internal class VanillaState(private val home: Path) {
    private val claudeJson = home.resolve(".claude.json").also {
        it.writeText(
            """{"mcpServers":{"ast-grep":{"command":"ast-grep"},"exa":{"command":"exa"}},""" +
                """"projects":{"/work/app":{"hasTrustDialogAccepted":true}},"oauthAccount":{"emailAddress":"op@example.test"}}""",
        )
    }
    private val dir = home.resolve(".claude").createDirectories()
    private val settings = dir.resolve("settings.json").also {
        it.writeText("""{"theme":"dark","hooks":{"Stop":[{"hooks":[{"type":"command","command":"operator-hook"}]}]}}""")
    }
    private val transcript = dir.resolve("projects/-work-app/s1.jsonl").also {
        it.parent.createDirectories()
        it.writeText("""{"type":"user"}""" + "\n")
    }

    private val before = snapshot()

    private fun snapshot(): Map<String, String> = buildMap {
        put(".claude.json", claudeJson.readText())
        Files.walk(dir).use { files ->
            files.filter { Files.isRegularFile(it) }.forEach { put(home.relativize(it).toString(), it.readText()) }
        }
    }

    fun assertUntouched() {
        assertEquals(before, snapshot(), "the vanilla ~/.claude.json and ~/.claude changed")
        check(settings.exists() && transcript.exists())
    }
}
