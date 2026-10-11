// NEW: the read set as a Gradle value, so the configuration cache can hold a build that depends on it.
//
// A law's read set is the file list git reports. Asked at configuration time by starting git, the build is not
// configuration-cache compatible: Gradle refuses an external process started by a build script. A ValueSource is how a
// build asks the outside world a question and keeps the cache honest: Gradle asks it again on EVERY build, reuses the
// cached configuration only when the answer is the same list, and reconfigures when git reports a different one. The
// answer is exactly what the eager call returned, produced by the same code, so a new, removed or renamed file still
// changes the task's inputs.
package splice.lawsuite

import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters

abstract class ReadSetListing : ValueSource<List<String>, ReadSetListing.Params> {
    interface Params : ValueSourceParameters {
        /** The repository root git is asked in. */
        val repo: DirectoryProperty

        /** The roots (or `:(glob)` pathspecs) listed, or none when [tracked] asks for every tracked file. */
        val roots: ListProperty<String>

        /** Every tracked file of the repository, instead of the files under [roots]. */
        val tracked: Property<Boolean>
    }

    override fun obtain(): List<String> {
        val repo = parameters.repo.get().asFile
        return if (parameters.tracked.get()) ReadSet.tracked(repo) else ReadSet.git(repo, parameters.roots.get())
    }
}
