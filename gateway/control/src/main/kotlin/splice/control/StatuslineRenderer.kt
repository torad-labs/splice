// PORT-OF: server/statusline/claudex-statusline.mjs @ pre-public-port-baseline — renders Claude Code's per-tick
// statusline from the JSON blob it pipes on stdin. Claude Code's shape: a top-level
// `context_window` object holding `context_window_size`, `used_percentage`, and a nested
// `current_usage.{input_tokens, cache_read_input_tokens, cache_creation_input_tokens}`
// (`total_input_tokens` is the pre-2.1.132 fallback). Segments: model dot + name, context
// used/window · pct (colored by proximity to compaction), cache-hit %, the soft-warn glyph, and
// the repo · branch. A parse failure falls back to a bare dim marker (never crashes the bar).
package splice.control

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import splice.core.usage.RateLimitState
import splice.core.usage.computeUsageWarn
import java.util.concurrent.TimeUnit

public class StatuslineRenderer(private val label: String) {
    private val json = Json { ignoreUnknownKeys = true }

    public fun render(stdinJson: String, usage: HeadUsageSource?, warnPct: Int, warnTokens5h: Long): String {
        val root = runCatching { json.parseToJsonElement(stdinJson).jsonObject }.getOrNull() ?: return dim(label)
        val segments = listOfNotNull(
            modelSegment(root),
            contextSegment(root),
            cacheSegment(root),
            warnSegment(usage, warnPct, warnTokens5h),
            locationSegment(root),
        )
        return if (segments.isEmpty()) dim(label) else segments.joinToString(SEPARATOR)
    }

    private fun modelSegment(root: JsonObject): String? {
        val model = obj(root, "model") ?: return null
        val name = str(model["display_name"]) ?: str(model["id"]) ?: return null
        return "$BOLD$CYAN●$RESET $BOLD$name$RESET"
    }

    private fun contextSegment(root: JsonObject): String? {
        val cw = obj(root, "context_window") ?: return null
        val size = num(cw["context_window_size"]) ?: 0
        val used = usedTokens(cw)
        val pct = num(cw["used_percentage"])?.toInt() ?: if (size > 0) (used * PERCENT / size).toInt() else 0
        val color = when {
            pct >= CTX_CRITICAL_PCT -> RED
            pct >= CTX_WARN_PCT -> YELLOW
            else -> GREEN
        }
        val window = if (size > 0) "${fmtK(used)}/${fmtK(size)}" else fmtK(used)
        return "$window ${dim("·")} $color$pct%$RESET"
    }

    private fun cacheSegment(root: JsonObject): String? {
        val cu = obj(obj(root, "context_window"), "current_usage") ?: return null
        val hit = cacheHitPct(cu) ?: return null
        return "${cacheColor(hit)}⚡ $hit%$RESET"
    }

    private fun cacheHitPct(cu: JsonObject): Int? {
        val read = num(cu["cache_read_input_tokens"]) ?: 0
        val total = (num(cu["input_tokens"]) ?: 0) + read + (num(cu["cache_creation_input_tokens"]) ?: 0)
        return if (total <= 0) null else (read * PERCENT / total).toInt()
    }

    private fun cacheColor(hit: Int): String = when {
        hit >= CACHE_GOOD_PCT -> GREEN
        hit >= CACHE_OK_PCT -> YELLOW
        else -> DIM
    }

    private fun warnSegment(usage: HeadUsageSource?, warnPct: Int, warnTokens5h: Long): String? {
        val source = usage ?: return null
        val snapshot = source.snapshot()
        val ratelimit = snapshot.ratelimit?.let {
            RateLimitState(it.limitTokens, it.remainingTokens, it.resetTokens)
        }
        val warn = computeUsageWarn(snapshot.outputTokens5h, ratelimit, warnPct, warnTokens5h)
        return when (warn.level) {
            "critical" -> "$RED⚠ ${warn.pct}%$RESET"
            "warn" -> "$YELLOW⚠ ${warn.pct}%$RESET"
            else -> null
        }
    }

    private fun locationSegment(root: JsonObject): String? {
        val cwd = str(obj(root, "workspace")?.get("current_dir")) ?: str(root["cwd"]) ?: return null
        val base = cwd.trim('/').substringAfterLast('/').ifEmpty { return null }
        // Only git when cwd is a real absolute directory under the user home (or /tmp) — never
        // exec git -C against an attacker-chosen path from unauthenticated /statusline.
        val branch = if (isSafeGitCwd(cwd)) gitBranch(cwd) else ""
        val loc = if (branch.isEmpty()) base else "$base  ⎇ $branch"
        return dim(loc)
    }

    /** current_usage.* is the correct per-turn count on every version; total_input_tokens is the
     * pre-2.1.132 fallback. */
    private fun usedTokens(cw: JsonObject): Long {
        val cu = obj(cw, "current_usage") ?: return num(cw["total_input_tokens"]) ?: 0
        return (num(cu["input_tokens"]) ?: 0) +
            (num(cu["cache_read_input_tokens"]) ?: 0) +
            (num(cu["cache_creation_input_tokens"]) ?: 0)
    }

    private fun gitBranch(cwd: String): String = runCatching {
        val process = ProcessBuilder("git", "-C", cwd, "branch", "--show-current")
            .redirectErrorStream(false)
            .start()
        if (!process.waitFor(GIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            return ""
        }
        process.inputStream.readBytes().decodeToString().trim()
    }.getOrDefault("")

    private companion object {
        fun obj(parent: JsonObject?, key: String): JsonObject? = parent?.get(key) as? JsonObject
        fun str(element: JsonElement?): String? = (element as? JsonPrimitive)?.content?.takeIf { it.isNotEmpty() }
        fun num(element: JsonElement?): Long? = (element as? JsonPrimitive)?.content?.toDoubleOrNull()?.toLong()
        fun fmtK(n: Long): String = if (n >= K) "${n / K}k" else n.toString()
        fun dim(s: String) = "$DIM$s$RESET"

        /** Absolute, existing directory under $HOME or /tmp, with ".." rejected after normalize. */
        fun isSafeGitCwd(cwd: String): Boolean {
            if (!cwd.startsWith("/")) return false
            if (cwd.any { it.code == 0 }) return false
            val path = runCatching { java.nio.file.Paths.get(cwd).toAbsolutePath().normalize() }.getOrNull()
                ?: return false
            if (path.toString().contains("..")) return false
            val home = runCatching {
                java.nio.file.Paths.get(System.getProperty("user.home")).toAbsolutePath().normalize()
            }.getOrNull()
            val tmp = java.nio.file.Paths.get("/tmp").toAbsolutePath().normalize()
            val underHome = home != null && path.startsWith(home)
            val underTmp = path.startsWith(tmp)
            if (!underHome && !underTmp) return false
            return java.nio.file.Files.isDirectory(path)
        }

        const val RESET = "[0m"
        const val DIM = "[2m"
        const val BOLD = "[1m"
        const val CYAN = "[36m"
        const val GREEN = "[32m"
        const val YELLOW = "[33m"
        const val RED = "[31m"
        const val SEPARATOR = "[2m   [0m"
        const val PERCENT = 100
        const val CTX_CRITICAL_PCT = 85
        const val CTX_WARN_PCT = 60
        const val CACHE_GOOD_PCT = 70
        const val CACHE_OK_PCT = 40
        const val K = 1000
        const val GIT_TIMEOUT_MS = 200L
    }
}
