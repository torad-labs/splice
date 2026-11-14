// NEW: Nov 12, 2026: a release lists one jar per platform beside splice.jar, the jar that holds the code-mode isolate of
// all four (about 476 MB to a platform's 210 MB). This is the name a platform's own jar is published under, as
// install.sh spells it (platform_jar there) and as the build cuts it (PlatformJar in build-logic).
package splice.lifecycle.upgrade

/** The name a release gives the jar of the machine this process runs on, from the JVM's own words for its system and
 *  processor. Null for a system or a processor no jar is cut for. A name the release does not list is not taken: the
 *  caller reads the release's sums, so an Intel Mac, which has a name here and no jar there, takes the jar of all four. */
internal class PlatformJarName(
    private val os: String = System.getProperty("os.name").orEmpty(),
    private val arch: String = System.getProperty("os.arch").orEmpty(),
) {
    private val systems = mapOf("Linux" to "linux", "Mac" to "darwin", "Windows" to "windows")
    private val processors =
        mapOf("amd64" to "amd64", "x86_64" to "amd64", "aarch64" to "aarch64", "arm64" to "aarch64")

    fun jar(): String? {
        val system = systems.entries.firstOrNull { (word, _) -> os.startsWith(word, ignoreCase = true) }?.value
        val processor = processors[arch.lowercase()]
        return if (system == null || processor == null) null else "splice-$system-$processor.jar"
    }
}
