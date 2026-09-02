// NEW (split out of LoginInterception.kt 2026-09-02): the filesystem mechanics every generated
// hook shares — prove the config dir can execute an owner-only script, stage-and-swap a script
// into place with its mode proven, and the settings.json entry that registers it. The login and
// key-capture policy (which legs fail closed, which degrade loudly) stays in LoginInterception.
package splice.core.launch

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import splice.core.util.Cancellables
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit

/** Applies the owner-only executable mode to a generated hook script. A seam because the one step a
 *  test must be able to fail on demand is exactly this one — no temp filesystem refuses a chmod —
 *  and the capture hook's entire security value is that the mode took. */
internal fun interface HookChmod {
    operator fun invoke(script: Path, perms: Set<PosixFilePermission>)
}

/** Proves [dir] can EXECUTE a fresh owner-only script, or names why not (DR-8 redo-2, 2026-08-31).
 *  Chmod success is not executability: on a noexec mount every mode bit sets while exec returns
 *  EACCES, so a registered hook can never run — and for the capture hook that means a pasted
 *  credential reaches the model. Only an actual exec settles it; injected so the counterexample
 *  is a deterministic test rather than a root-only noexec mount. */
internal fun interface HookExecProbe {
    operator fun invoke(dir: Path, chmod: HookChmod): Throwable?
}

internal object HookScriptFiles {
    const val HOOK_TIMEOUT_SECONDS: Int = 15

    /** The real [HookExecProbe]: write a throwaway owner-only `exit 0` script beside the hooks and
     *  RUN it. Executability is a property of the mount + mode + uid, not of content, so a sibling
     *  probe file proves exactly what the hook needs without executing any hook logic. EACCES from
     *  a noexec mount surfaces here as ProcessBuilder's IOException — the codex /run/lock repro. */
    fun probeExecutability(dir: Path, chmod: HookChmod): Throwable? {
        // DR-8 redo-3 (codex symlink catch): a FIXED ".splice-exec-probe.tmp" was a predictable
        // path a local peer could pre-plant as a symlink (the write would follow it and clobber the
        // victim) and a shared name two concurrent launches raced. createTempFile picks a random
        // name and creates it with CREATE_NEW (O_EXCL), which refuses ANY pre-existing path —
        // symlink or dangling symlink included — so the write can only land on the fresh regular
        // file it just made. Creation runs INSIDE the try, so a creation failure returns as the
        // probe's Throwable (fail-closed); the finally deletes only a probe that was created.
        var probe: Path? = null
        return try {
            probe = Files.createTempFile(dir, ".splice-exec-probe.", ".tmp")
            Files.writeString(probe, "#!/bin/sh\nexit 0\n")
            chmod(probe, PosixFilePermissions.fromString("rwx------"))
            val process = ProcessBuilder(probe.toString()).redirectErrorStream(true).start()
            if (!process.waitFor(HOOK_TIMEOUT_SECONDS.toLong(), TimeUnit.SECONDS)) {
                process.destroyForcibly()
                IOException("exec probe timed out after ${HOOK_TIMEOUT_SECONDS}s")
            } else if (process.exitValue() != 0) {
                IOException("exec probe exited ${process.exitValue()}")
            } else {
                null
            }
        } catch (e: IOException) {
            e
        } catch (e: UnsupportedOperationException) {
            e
        } catch (e: SecurityException) {
            e
        } finally {
            probe?.let { p -> Cancellables.runCatchingCancellable { Files.deleteIfExists(p) } }
        }
    }

    /**
     * The generated hook script, owner-only and EXECUTABLE — or an [IOException] (DR-8 redo).
     *
     * A chmod failure used to be logged and shallowed here, which quietly defeated the fail-closed
     * capture leg above: the script was written 0644, [hookEntry] registered its path in
     * settings.json, and Claude Code could not run it. A registered hook that cannot execute is
     * indistinguishable from no hook at all, so for the capture leg it means a pasted credential
     * reaches the model — the exact outcome that leg exists to prevent. Throwing instead lets each
     * caller's EXISTING wrap decide the policy: the login and advertiser legs catch it and log a
     * dropped leg, the unwrapped capture leg fails the launch.
     *
     * The condition is the OUTCOME, not the call: [Files.isExecutable] holds on a filesystem that
     * ignores modes but mounts exec (the LNC-005 case this used to tolerate wholesale) and fails on
     * one that leaves the script unrunnable, whatever the chmod itself reported.
     */
    /** Stage-and-swap (DR-31): Claude Code parses these scripts on every prompt, so the LIVE hook
     *  must never be observable truncated, torn, or mode-broken. Content and mode land on a staged
     *  same-dir copy FIRST; the atomic move publishes it whole (rename keeps the inode, so the
     *  proven mode travels with it). Any failure deletes only the staged copy — a pre-existing
     *  working hook stays untouched and the launch fails loudly.
     *
     *  The chmod outcome is the ONLY mode probe (DR-8 redo: a seeded rwxrwxrwx file passes
     *  isExecutable while anyone may rewrite what the hook runs), and the catch net is wider than
     *  runCatchingCancellable's IO/serialization/IAE because setPosixFilePermissions also throws
     *  UnsupportedOperationException (non-POSIX fs) and SecurityException (second DR-8 redo). */
    fun writeHookScript(configDir: Path, name: String, content: String, chmod: HookChmod): Path {
        val script = configDir.resolve(name)
        // DR-8/DR-31 redo (codex): a FIXED "$name.tmp" stage was a predictable path (symlink
        // pre-plant → the write clobbers a victim) AND a shared name two concurrent launches raced
        // (A's move published B's body). createTempFile picks a unique random name and creates it
        // with CREATE_NEW (O_EXCL) in the SAME dir, so each launch owns its own stage and the write
        // cannot follow a pre-existing symlink. The finally removes the stage on EVERY exit that
        // did not consume it by move — write failure, chmod failure, an interrupt/cancellation
        // mid-write — so no ".tmp" is ever stranded; the live hook is untouched until move succeeds.
        val staged = Files.createTempFile(configDir, "$name.", ".tmp")
        var moved = false
        try {
            Files.writeString(staged, content)
            val chmodFailure = try {
                Cancellables.runCatchingCancellable {
                    chmod(staged, PosixFilePermissions.fromString("rwx------"))
                }.exceptionOrNull()
            } catch (e: UnsupportedOperationException) {
                e
            } catch (e: SecurityException) {
                e
            }
            if (chmodFailure != null) {
                throw IOException(
                    // SAFE-RENDER-EXEMPT[2026-08-31]: a chmod on a copy we just wrote — the failure names that path, never the script's bytes
                    "$script: chmod rwx------ failed on the staged copy (${chmodFailure.message}) — " +
                        "staged file deleted, any existing hook left untouched",
                )
            }
            Files.move(staged, script, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            moved = true
            return script
        } finally {
            if (!moved) Cancellables.runCatchingCancellable { Files.deleteIfExists(staged) }
        }
    }

    fun hookEntry(script: Path, timeoutSeconds: Int): JsonObject = buildJsonObject {
        putJsonArray("hooks") {
            addJsonObject {
                put("type", "command")
                put("command", script.toString())
                put("timeout", timeoutSeconds)
            }
        }
    }
}
