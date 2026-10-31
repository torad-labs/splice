// NEW: strict bounded fatal-worker diagnostics stay distinct from guest completion frames.
package splice.codemode

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.upstream.failure.CodeModeInfrastructureCategory
import splice.upstream.failure.CodeModeInfrastructureClass
import splice.upstream.failure.CodeModeInfrastructureException
import splice.upstream.failure.CodeModeWorkerLostException
import java.io.IOException

internal const val CODE_MODE_FATAL_FRAME_TYPE: String = "fatal"

/** What a worker task died of, as "Throwable in Class.method", and the reply it still owes its cell, if any. */
internal data class CodeModeTaskDeath(val description: String, val reply: JsonObject?) {
    /** The reply the task still owes its cell. With none, each request waiting on the dead worker fails as lost. */
    fun owed(): JsonObject =
        reply ?: throw CodeModeWorkerLostException(IOException("Code-mode worker task died: $description"))
}

internal object CodeModeFatalFrame {
    private const val CATEGORY: String = "category"
    private const val FAULT_CLASS: String = "faultClass"
    private const val TYPE: String = "type"
    private const val DIED_THROWABLE: String = "throwable"
    private const val DIED_AT: String = "at"
    private const val OWED_REPLY: String = "reply"
    private val DIED_KEYS = setOf(TYPE, CATEGORY, FAULT_CLASS, DIED_THROWABLE, DIED_AT)

    // A class or method name, never source text: the line it feeds goes to daemon.log.
    private val NAME = Regex("[A-Za-z0-9_$.<>-]{1,128}")

    /**
     * The frame a worker writes when a task dies of [failure], naming its class and the first splice frame of its
     * trace, or the top frame when the trace holds none. A [reply] the task still produced rides along.
     */
    fun died(failure: VirtualMachineError, reply: JsonObject? = null): JsonObject = buildJsonObject {
        put(TYPE, CODE_MODE_FATAL_FRAME_TYPE)
        put(CATEGORY, CodeModeInfrastructureCategory.HOST.name)
        put(FAULT_CLASS, CodeModeInfrastructureClass.RUNTIME.name)
        put(
            DIED_THROWABLE,
            when (failure) {
                is OutOfMemoryError -> "OutOfMemoryError"
                is StackOverflowError -> "StackOverflowError"
                is InternalError -> "InternalError"
                else -> "VirtualMachineError"
            },
        )
        val trace = failure.stackTrace
        val frame = trace.firstOrNull { it.className.startsWith("splice.") } ?: trace.firstOrNull()
        put(DIED_AT, frame?.let { "${it.className.substringAfterLast('.')}.${it.methodName}" } ?: "unknown.frame")
        reply?.let { put(OWED_REPLY, it) }
    }

    /** The task death [frame] reports, or null when it reports none. */
    fun death(frame: JsonObject): CodeModeTaskDeath? {
        if (DIED_THROWABLE !in frame) return null
        if (frame.keys - OWED_REPLY != DIED_KEYS || string(frame, TYPE) != CODE_MODE_FATAL_FRAME_TYPE) invalid()
        val names = listOf(string(frame, DIED_THROWABLE), string(frame, DIED_AT))
        if (!names.all(NAME::matches)) invalid()
        val reply = frame[OWED_REPLY]?.let { it as? JsonObject ?: invalid() }
        return CodeModeTaskDeath("${names[0]} in ${names[1]}", reply)
    }

    fun create(
        category: CodeModeInfrastructureCategory,
        faultClass: CodeModeInfrastructureClass,
    ): JsonObject = buildJsonObject {
        put(TYPE, CODE_MODE_FATAL_FRAME_TYPE)
        put(CATEGORY, category.name)
        put(FAULT_CLASS, faultClass.name)
    }

    fun parse(frame: JsonObject): Nothing {
        if (frame.keys != setOf(TYPE, CATEGORY, FAULT_CLASS)) invalid()
        val category = CodeModeInfrastructureCategory.entries.firstOrNull { candidate ->
            candidate.name == string(frame, CATEGORY)
        } ?: invalid()
        val faultClass = CodeModeInfrastructureClass.entries.firstOrNull { candidate ->
            candidate.name == string(frame, FAULT_CLASS)
        } ?: invalid()
        throw CodeModeInfrastructureException(category, faultClass)
    }

    private fun string(frame: JsonObject, name: String): String =
        (frame[name] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content ?: invalid()

    private fun invalid(): Nothing = throw IOException("Code-mode worker sent invalid fatal diagnostics")
}
