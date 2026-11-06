package splice.upstream.codemode

import splice.core.perf.observation.PerfLinks
import splice.core.perf.observation.PerfRequestRecorder
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Request ownership crosses the runtime seam without depending on a tracing SDK or a process-global recorder. */
public class CodeModePerfContext(
    public val request: PerfRequestRecorder?,
    public val attempt: PerfLinks? = null,
) : AbstractCoroutineContextElement(CodeModePerfContextKey)

public object CodeModePerfContextKey : CoroutineContext.Key<CodeModePerfContext>
