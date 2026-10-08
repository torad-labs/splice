// NEW: 2026-10-08 — the guard that a law cannot silence.
//
// LawReadSet throws on a read outside its declared set, and a law can catch a throw: three sites did (a `runCatching` around a
// read returned false, another returned no findings). So the refusal is also RECORDED here, in the law-suite runtime, before it
// throws, and LawReadGuard — registered by the JUnit service loader in every lawTest JVM (splice.law-suite turns autodetection
// on) — fails the test after it runs if anything was recorded, whatever the test did with the throw.
package splice.core.testing

import org.junit.jupiter.api.extension.AfterAllCallback
import org.junit.jupiter.api.extension.AfterEachCallback
import org.junit.jupiter.api.extension.ExtensionContext

/** The undeclared reads refused since the last [drain]. */
object UndeclaredReads {
    private val refused = mutableListOf<String>()

    fun record(relative: String) = synchronized(refused) { refused += relative }

    /** The recorded refusals, in order; the record is emptied. */
    fun drain(): List<String> = synchronized(refused) { refused.toList().also { refused.clear() } }
}

/** Fails a test after it ran when [UndeclaredReads] holds a refusal, even if the test caught the throw, and fails the class the
 *  same way after its own @AfterAll methods ran (JUnit runs extension callbacks after them), which covers @BeforeAll, @AfterAll
 *  and initializer reads. */
class LawReadGuard : AfterEachCallback, AfterAllCallback {
    override fun afterEach(context: ExtensionContext) = failOnRefusals()

    override fun afterAll(context: ExtensionContext) = failOnRefusals()

    private fun failOnRefusals() {
        val refused = UndeclaredReads.drain()
        check(refused.isEmpty()) {
            "a law read outside the read set the build declared for it, and the refusal was swallowed: $refused"
        }
    }
}
