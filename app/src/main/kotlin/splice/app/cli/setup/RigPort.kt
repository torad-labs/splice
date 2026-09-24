// NEW: the ONE port `splice setup` reaches rig through, and the seams its local-model step asks the
// machine with. rig is a separate program (torad-labs/rig): it installs itself, builds llama.cpp for
// the card, downloads the model and serves it as an OpenAI-compatible endpoint on 127.0.0.1. The
// wizard asks it four things — prepare, up, describe, and its version — and adds a head for what it
// serves; it never learns how rig does any of it.
//
// A small INTERFACE rather than one `fun interface` per verb: the five calls are one collaborator.
// A test replaces them together with one fake and production runs them through one process runner
// (ProcessRig). EVERY test hands over its own: the real one downloads a 7 GB model, and on the
// operator's box a model is already serving on rig's port.
package splice.app.cli.setup

import splice.configuration.add.RuntimeHead

/** The head rig serves, and the key and command splice gives it. */
internal const val RIG_HEAD: String = "bonsai-2-27b"
internal const val LOCAL_KEY: String = "bonsai"
internal const val LOCAL_COMMAND: String = "claude-$LOCAL_KEY"

/** rig's installer exactly as its release page gives it, printed before it runs so the operator sees
 *  what is about to execute on their machine. */
internal const val RIG_INSTALL: String =
    "curl -fsSL https://github.com/torad-labs/rig/releases/latest/download/install.sh | sh"

/** One finished rig call: its exit code and both streams, whole. */
internal data class RigRun(val exit: Int, val stdout: String, val stderr: String) {

    /** The last [count] non-blank stderr lines — where rig says what went wrong. */
    fun tail(count: Int): List<String> = stderr.lines().filter { it.isNotBlank() }.takeLast(count)
}

/** One line `rig up` wrote to stderr as it arrived: a coarse `== <step>` marker or the one-line
 *  detail under it. Progress for the spinner, never a result. */
internal fun interface RigProgress {
    operator fun invoke(line: String)
}

/** The rig command line, as the setup wizard drives it. Production is [ProcessRig]. */
internal interface Rig {

    /** `rig --version`, from PATH or ~/.local/bin/rig: exit 0 means rig is installed. */
    fun version(): RigRun

    /** rig's own installer, [RIG_INSTALL]. */
    fun install(): RigRun

    /** `rig prepare --json`: can this card run the engine, and is every tool present. */
    fun prepare(): RigRun

    /** `rig up <head> --json`: fetch, build, derive, unit, start. Never `--restart`. */
    fun up(head: String, progress: RigProgress): RigRun

    /** `rig describe <head>`: where the head serves and what its server does. */
    fun describe(head: String): RigRun
}

/** The NVIDIA cards on this machine, by name; empty when there is none, or no nvidia-smi to ask. */
internal fun interface GpuProbe {
    operator fun invoke(): List<String>
}

/** The OS and CPU the JVM reports. rig ships for Linux x86_64 only, so nothing else is offered it. */
internal data class HostPlatform(val os: String, val arch: String) {

    fun rigSupported(): Boolean = os == "Linux" && arch in X86_64
}

/** Adds the head rig described through `splice add`'s machinery (AddVerb.addRuntime): its refusals,
 *  checks, atomic save and restart. The wizard never writes a topology table itself. */
internal fun interface LocalHeadAdd {
    suspend operator fun invoke(head: RuntimeHead): Boolean
}

/** Both spellings of x86_64 a JVM reports: `amd64` (OpenJDK on Linux) and `x86_64` (some vendors). */
private val X86_64 = setOf("amd64", "x86_64")
