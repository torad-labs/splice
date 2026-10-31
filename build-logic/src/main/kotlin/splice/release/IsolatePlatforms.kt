// The code-mode isolate libraries a fat jar can carry, one per platform GraalVM publishes, and which of them a build ships.
package splice.release

/**
 * One list for the two places that must agree: the module that adds the libraries ([shipped]) and the release staging
 * that refuses a jar missing any of them ([every], [marker]). Each library is 140 to 150 MB, so a developer's own build
 * ships only the host's; a release ships all four, because an install on any of those hosts starts code mode's isolate
 * from the jar and cannot start it from a library the jar does not hold.
 */
object IsolatePlatforms {
    /** Every platform GraalVM publishes an isolate library for. */
    val every: List<String> = listOf("linux-amd64", "linux-aarch64", "darwin-aarch64", "windows-amd64")

    /** The platform of the machine running Gradle: the one library a local run can load. */
    fun host(os: String, arch: String): String {
        val system = os.lowercase()
        val processor = arch.lowercase()
        return when {
            system.startsWith("windows") -> "windows-amd64"
            system.startsWith("mac") -> "darwin-aarch64"
            processor == "aarch64" || processor == "arm64" -> "linux-aarch64"
            else -> "linux-amd64"
        }
    }

    /**
     * The platforms a build ships. [allPlatforms] is the `splice.allPlatforms` property when it was given, and otherwise
     * whether this is a hosted run (`CI` set), which is a release path. A local release or beta gives the property.
     */
    fun shipped(allPlatforms: Boolean?, hosted: Boolean, os: String, arch: String): List<String> =
        if (allPlatforms ?: hosted) every else listOf(host(os, arch))

    /** The jar entry that is present exactly when [platform]'s isolate library is inside a fat jar. */
    fun marker(platform: String): String = "META-INF/resources/engine/js-isolate-$platform/libvm/sha256"
}
