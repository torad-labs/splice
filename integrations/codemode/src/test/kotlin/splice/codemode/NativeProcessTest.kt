package splice.codemode

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.seconds

// Native stdio and process completion advance on wall time. Keep the whole test and
// its child observations off the virtual scheduler so real IPC gets time to answer.
internal fun nativeProcessTest(test: suspend CoroutineScope.() -> Unit): Unit = runTest(timeout = 30.seconds) {
    withContext(Dispatchers.Default, test)
}
