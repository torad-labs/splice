package splice.app.mods

import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import splice.app.mods.install.ModInstall
import splice.app.mods.install.ModRefusal
import splice.app.mods.install.ModSwitch
import splice.core.util.JsonScalars
import java.nio.file.InvalidPathException
import java.nio.file.Path

/** The management mutation bodies; invalid switches are refused rather than interpreted as disable or accept. */
internal object ModRequests {
    suspend fun install(call: ApplicationCall): ModInstall {
        val body = body(call, setOf("source", "link", "bunPath"))
        return ModInstall(
            source = absolute(string(body, "source") ?: throw ModRefusal("give the path to a mod folder or tarball")),
            linked = boolean(body, "link") ?: false,
            bunPath = string(body, "bunPath")?.let { absolute(it).toString() },
        )
    }

    suspend fun switch(call: ApplicationCall): ModSwitch {
        val body = body(call, setOf("enabled", "accept"))
        val enabled = boolean(body, "enabled") ?: throw ModRefusal("say whether to enable or disable the mod")
        val accept = boolean(body, "accept") ?: false
        if (accept && !enabled) throw ModRefusal("accepting changed files requires enabling the mod")
        return ModSwitch(enabled, accept)
    }

    private suspend fun body(call: ApplicationCall, fields: Set<String>): JsonObject {
        val body = JsonScalars.strictObjectOrNull(Json, call.receiveText())
            ?: throw ModRefusal("give a JSON object")
        if (!fields.containsAll(body.keys)) throw ModRefusal("this request takes only ${fields.joinToString()}")
        return body
    }

    private fun string(body: JsonObject, key: String): String? {
        val value = body[key] ?: return null
        return (value as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw ModRefusal("$key must be a string")
    }

    private fun boolean(body: JsonObject, key: String): Boolean? {
        val value = body[key] ?: return null
        return (value as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
            ?: throw ModRefusal("$key must be true or false")
    }

    private fun absolute(raw: String): Path {
        val path = try {
            Path.of(raw)
        } catch (_: InvalidPathException) {
            throw ModRefusal("give a valid absolute file path")
        }
        if (!path.isAbsolute) throw ModRefusal("give an absolute path on the daemon's machine")
        return path
    }
}
