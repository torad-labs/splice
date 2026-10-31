// System properties that name this checkout's absolute paths, handed to a test JVM without becoming part of the task's cache key.
package splice.testing

import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Internal
import org.gradle.process.CommandLineArgumentProvider

/** `-Dname=value` arguments whose values differ per checkout. They are @Internal: the files they point at are declared as inputs
 *  by relative path, so two checkouts of the same bytes share one cache entry instead of each keying on its own directory. */
class MachineLocalProperties(
    @get:Internal val properties: Provider<Map<String, String>>,
) : CommandLineArgumentProvider {
    override fun asArguments(): Iterable<String> = properties.get().map { (name, value) -> "-D$name=$value" }
}

/** Whole JVM arguments whose values differ per checkout, held out of the cache key for the same reason as [MachineLocalProperties]. */
class MachineLocalArguments(
    @get:Internal val arguments: Provider<List<String>>,
) : CommandLineArgumentProvider {
    override fun asArguments(): Iterable<String> = arguments.get()
}
