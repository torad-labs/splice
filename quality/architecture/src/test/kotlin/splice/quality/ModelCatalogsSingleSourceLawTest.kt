// NEW: V4-98 — the three hand-authored model rosters agree with app/src/main/resources/splice.example.toml
// (ported from checks/model-catalogs-single-source.ts, restructure PR 6).
//
// WHY THIS EXISTS. splice declares its model rows THREE times, by hand, in three languages: the
// example config an operator copies (ids, labels, context windows, per-provider commentary), the
// rows `splice add <vendor>` renders into the operator's file (AddProfileCatalog.kt), and
// TopologyLoader's DEFAULT_TOML, the starter materialized on first run. Nothing paired them. A
// window corrected in the example stayed wrong in the two emitters, and a model id added to an
// emitter never had to exist in the reference at all. A window that disagrees is not cosmetic:
// TopologyLoader plants the pinned row's window as CLAUDE_CODE_MAX_CONTEXT_TOKENS and
// ModelCatalog.usageScale compacts every other row against its declared number, so a roster that
// drifted by 4.6% compacts 4.6% early or late with nothing logging it.
//
// THE LAW. Every model row a DERIVED roster declares must exist in the example's roster for the
// SAME provider, with a byte-identical context window. The example may declare more — it is the
// full reference and the emitters are curated starters — so a superset there is not drift.
//
// THE JOIN KEY IS base_url, NEVER the table name. The provider keys disagree on purpose: the
// catalog calls xAI `grok` and Anthropic `claude` (the WRAPPER an operator types) while the example
// calls them `xai` and `anthropic` (the VENDOR). A hand-written alias map between them would be a
// fourth hand-authored list, and this law exists because hand-authored lists agree with each other.
// base_url is the provider's actual identity, declared in all three files, and it is what the
// daemon dials. Two example providers sharing one base_url make the join ambiguous and are RED.
//
// DENOMINATORS, FROM THE SOURCES, never a hand list. The example's providers and rows are parsed
// out of the TOML on disk. The catalog's rows are parsed out of AddProfileCatalog.kt's
// AddProfile/AddModel calls, with the WINDOW_* constants resolved from that same file's
// `private const val` declarations. DEFAULT_TOML is extracted from TopologyLoader.kt by its marker
// and parsed as TOML. Both Kotlin files are resolved THROUGH THE BUILD'S PROJECT MAP (:app), never
// a literal directory, so a module that moves keeps being graded. A vendor added to any of the
// three is in scope with no edit to this file.
//
// FOUR GUARDS REFUSE A VACUOUS PASS: the example must yield at least one provider carrying a model
// row; each derived source must yield at least one roster carrying a model row; the rows parsed out
// of each TOML must equal the count of `[[providers.*.models]]` headers in its comment-stripped
// text, and the AddModel rows parsed out of the catalog must equal the count of `AddModel(` calls
// in its comment-stripped text; and the number of (id, window) comparisons performed must be
// non-zero.
//
// DISPOSITION. agrees — joined by base_url, every id present, every window equal; no-roster — the
// roster declares NO base_url and NO models, so there is nothing to compare (the generic `api-key`
// row, whose base URL and models the operator supplies on the command line). That condition is
// COMPUTED, not a named exemption: a row that grows models while keeping a null base_url stops
// qualifying and goes red. Anything else is RED BY NAME. Absence is not a disposition.
//
// NOT CAUGHT, and why. A label that disagrees — display strings shown in different places, and
// pinning them would red the wall for a copy edit; ids and windows are the wire. A model the
// example declares and an emitter omits — deliberate, the emitters are curated starters. Slots,
// rates and quirks — other walls own those. A window that is wrong in the EXAMPLE — that is a
// live-probe job; this law makes the three agree.
package splice.quality

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** THE PARSE, split from the law it feeds: three files in, one roster list each out, and every
 *  reading that cannot be trusted named rather than dropped. Text in, data out — nothing here
 *  knows what the example IS to the emitters, which is [ModelCatalogsSingleSource]'s half. */
internal object ModelRosters {
    const val STARTER_MARKER = "DEFAULT_TOML"

    private const val RAW_QUOTE = "\"\"\""
    private const val BLOCK_CLOSE = "*/"

    /** `[providers.x]` / `[[providers.x.models]]`, as depth-3 dotted paths. */
    private const val MODELS_PATH_DEPTH = 3
    private const val WINDOW_ARGUMENT = 2

    // `\z` and not `$` on every anchor that sees RAW LINE TEXT: Java's `$` also matches before a
    // final line terminator where JS's does not, so a CRLF line would match here and not there.
    // MODELS_HEADER keeps `$` because it is MULTILINE, where the two agree.
    private val TABLE = Regex("^[ \\t]*(\\[\\[?)([^\\[\\]\\n]+)\\]\\]?[ \\t]*\\z")
    private val KV = Regex("^[ \\t]*([A-Za-z0-9_-]+)[ \\t]*=[ \\t]*(.*)\\z")
    private val MODELS_HEADER =
        Regex("^[ \\t]*\\[\\[providers\\.[^\\[\\]\\n]+\\.models\\]\\][ \\t]*$", RegexOption.MULTILINE)
    private val WINDOW_CONST = Regex("\\bconst\\s+val\\s+(WINDOW_\\w+)\\s*=\\s*([0-9_]+)L?\\b")
    private val ADD_MODEL_CALL = Regex("\\bAddModel\\s*\\(")
    private val ADD_PROFILE_CALL = Regex("\\bAddProfile\\s*\\(")
    private val NUMBER = Regex("^([0-9_]+)L?$")
    private val STRING_ARG = Regex("^\"((?:[^\"\\\\]|\\\\.)*)\"$")
    private val NAME_ARG = namedArgPattern("name")
    private val BASE_URL_ARG = namedArgPattern("baseUrl")
    private val MODELS_ARG = namedArgPattern("models")

    private fun namedArgPattern(name: String) = Regex("^\\s*${Regex.escape(name)}\\s*=\\s*([\\s\\S]*)$")

    /** One model row: the id on the wire and the context window declared beside it. */
    data class Model(val id: String, val window: Long?)

    /** One provider's declared model rows, as parsed out of one file. */
    data class Roster(
        val label: String,
        val provider: String,
        var baseUrl: String?,
        val models: MutableList<Model> = mutableListOf(),
    ) {
        val where: String get() = "$label [$provider]"
    }

    /** What one file yielded, and everything that made the yield untrustworthy. */
    data class Parsed(val rosters: List<Roster>, val problems: List<String>)

    /** DEFAULT_TOML's body, or the reason it could not be read. */
    data class Starter(val text: String?, val problems: List<String>)

    /** One AddProfile row and the raw text of its `models = …` argument. */
    private data class Profile(val roster: Roster, val models: String)

    // ── readers ───────────────────────────────────────────────────────────────────────────────

    /** One line's comment-stripping state: which quote is open, and whether an escape is pending. */
    private class TomlLine {
        private val kept = StringBuilder()
        private var quote: Char? = null
        private var escape = false

        /** Keeps [ch]; false once a `#` OUTSIDE a string ends the line. */
        fun accept(ch: Char): Boolean {
            val opensComment = quote == null && ch == '#'
            if (opensComment) return false
            kept.append(ch)
            if (quote == null) opensString(ch) else inString(ch)
            return true
        }

        private fun opensString(ch: Char) {
            if (ch == '"' || ch == '\'') quote = ch
        }

        private fun inString(ch: Char) {
            when {
                escape -> escape = false
                ch == '\\' -> escape = true
                ch == quote -> quote = null
            }
        }

        /** The kept text; trailing blanks go with the comment. */
        fun kept(): String = kept.toString().trimEnd()
    }

    private fun stripTomlLine(line: String): String {
        val state = TomlLine()
        for (ch in line) {
            if (!state.accept(ch)) break
        }
        return state.kept()
    }

    /** Drop `#` comments, respecting quoted strings. Line structure survives so a header regex over
     *  the stripped text still anchors. */
    fun stripTomlComments(text: String): String = text.split("\n").joinToString("\n") { stripTomlLine(it) }

    /** Remove `//` and block comments, respecting string literals (raw triple-quoted included).
     *  Offsets are NOT preserved — this view exists only to COUNT the calls the parser must find. */
    fun stripKotlinComments(source: String): String {
        val out = StringBuilder(source.length)
        var i = 0
        while (i < source.length) {
            val ch = source[i]
            i = when {
                source.startsWith(RAW_QUOTE, i) -> copyRawString(source, i, out)
                ch == '"' || ch == '\'' -> copyQuoted(source, i, out)
                source.startsWith("//", i) -> skipLineComment(source, i)
                source.startsWith("/*", i) -> skipBlockComment(source, i)
                else -> {
                    out.append(ch)
                    i + 1
                }
            }
        }
        return out.toString()
    }

    private fun copyRawString(source: String, at: Int, out: StringBuilder): Int {
        out.append(RAW_QUOTE)
        var i = at + RAW_QUOTE.length
        while (i < source.length && !source.startsWith(RAW_QUOTE, i)) {
            out.append(source[i])
            i += 1
        }
        val closed = i < source.length
        if (closed) out.append(RAW_QUOTE)
        return if (closed) i + RAW_QUOTE.length else i
    }

    private fun copyQuoted(source: String, at: Int, out: StringBuilder): Int {
        val quote = source[at]
        out.append(quote)
        var i = at + 1
        var escape = false
        while (i < source.length) {
            val ch = source[i]
            out.append(ch)
            i += 1
            val closes = !escape && ch == quote
            escape = !escape && ch == '\\'
            if (closes) break
        }
        return i
    }

    private fun skipLineComment(source: String, at: Int): Int {
        val newline = source.indexOf('\n', at)
        return if (newline < 0) source.length else newline
    }

    private fun skipBlockComment(source: String, at: Int): Int {
        val end = source.indexOf(BLOCK_CLOSE, at + 2)
        return if (end < 0) source.length else end + BLOCK_CLOSE.length
    }

    /** A TOML/Kotlin scalar's string value, or null when it is not a quoted string. Escapes are
     *  NOT decoded, exactly as the checker left them: the comparison is between two spellings of
     *  the same literal, never between two decoded values. */
    fun unquote(raw: String): String? = STRING_ARG.find(raw.trim())?.groupValues?.get(1)

    fun parseNumber(raw: String): Long? =
        NUMBER.find(raw.trim())?.groupValues?.get(1)?.replace("_", "")?.toLongOrNull()

    // ── the TOML roster walk ──────────────────────────────────────────────────────────────────

    /** The walker's state, as one object — the direct port of the checker's four closed-over names. */
    private class TomlScan(val label: String) {
        val rosters = linkedMapOf<String, Roster>()
        val problems = mutableListOf<String>()
        var provider: String? = null
        var inModel = false
        var modelId: String? = null
        var modelWindow: Long? = null
        var rowsParsed = 0

        fun roster(name: String): Roster = rosters.getOrPut(name) { Roster(label, name, null) }

        fun closeModel() {
            if (!inModel) return
            rowsParsed += 1
            val current = provider
            val id = modelId
            when {
                current == null -> problems += "$label: a [[providers.*.models]] row outside any provider table"
                id == null -> problems += "$label [$current]: a model row declares no id"
                else -> roster(current).models += Model(id, modelWindow)
            }
            inModel = false
            modelId = null
            modelWindow = null
        }

        fun header(match: MatchResult) {
            closeModel()
            val parts = match.groupValues[2].trim().split(".")
            val name = parts.getOrNull(1)
            val ofProviders = parts[0] == "providers" && name != null
            if (!ofProviders || name == null) {
                provider = null
            } else if (parts.size == 2) {
                provider = name
                roster(name)
            } else {
                modelHeader(match.groupValues[1], parts, name)
            }
        }

        /** A depth-3 `[[providers.X.models]]` opens a row; every other nested table keeps the
         *  current provider so a later base_url cannot be mis-attributed. */
        private fun modelHeader(opener: String, parts: List<String>, name: String) {
            val shaped = parts.size == MODELS_PATH_DEPTH && parts.last() == "models"
            if (shaped && opener == "[[") {
                provider = name
                roster(name)
                inModel = true
            } else {
                provider = if (rosters.containsKey(name)) name else provider
            }
        }

        fun pair(match: MatchResult, current: String) {
            val key = match.groupValues[1]
            val raw = match.groupValues[2]
            when {
                inModel && key == "id" -> modelId = unquote(raw)
                inModel && key == "context_window" -> modelWindow = parseNumber(raw)
                inModel -> Unit
                key == "base_url" -> roster(current).baseUrl = unquote(raw)
            }
        }
    }

    /** Providers and their `[[providers.X.models]]` rows out of TOML text. `extra_windows` and
     *  every non-provider table (heads, daemon, quirks) carry no model rosters and are skipped. */
    fun parseTomlRosters(text: String, label: String): Parsed {
        val scan = TomlScan(label)
        val stripped = stripTomlComments(text)
        for (line in stripped.split("\n")) {
            val header = TABLE.find(line)
            val pair = if (header == null) KV.find(line) else null
            val current = scan.provider
            when {
                header != null -> scan.header(header)
                pair != null && current != null -> scan.pair(pair, current)
            }
        }
        scan.closeModel()
        val rawRows = MODELS_HEADER.findAll(stripped).count()
        if (rawRows != scan.rowsParsed) {
            scan.problems += "$label: parsed ${scan.rowsParsed} model rows but the text holds $rawRows " +
                "[[providers.*.models]] headers — the parser and the source disagree, so no " +
                "roster from this run can be trusted"
        }
        return Parsed(scan.rosters.values.toList(), scan.problems)
    }

    // ── the Kotlin catalog walk ───────────────────────────────────────────────────────────────

    /** The text inside the parens whose opener is at or after [openIndex] — comment- and
     *  string-aware, and counting ONLY parentheses, exactly as the checker's `parenBody` did. */
    fun parenBody(source: String, openIndex: Int): String? {
        val kinds = KotlinText.kinds(source, openIndex)
        var depth = 0
        var bodyStart = -1
        for (i in openIndex until source.length) {
            val code = kinds[i] == KotlinText.CODE
            if (!code) continue
            if (source[i] == '(') {
                depth += 1
                if (depth == 1) bodyStart = i + 1
            } else if (source[i] == ')') {
                depth -= 1
                if (depth == 0 && bodyStart >= 0) return source.substring(bodyStart, i)
            }
        }
        return null
    }

    private fun bracketDelta(ch: Char): Int = when (ch) {
        in "({[" -> 1
        in ")}]" -> -1
        else -> 0
    }

    /** Split a call's argument list on top-level commas, dropping comments. The LAST part is kept
     *  only when it is NON-BLANK, so a trailing comma yields no phantom positional argument —
     *  which is why this is not [KotlinText.splitTopLevel]. */
    fun splitArgs(body: String): List<String> {
        val kinds = KotlinText.kinds(body)
        val parts = mutableListOf<String>()
        val buf = StringBuilder()
        var depth = 0
        for (i in body.indices) {
            if (kinds[i] == KotlinText.COMMENT) continue
            val ch = body[i]
            val code = kinds[i] == KotlinText.CODE
            if (code) depth += bracketDelta(ch)
            val separates = code && ch == ','
            if (separates && depth == 0) {
                parts += buf.toString()
                buf.setLength(0)
            } else {
                buf.append(ch)
            }
        }
        if (buf.toString().isNotBlank()) parts += buf.toString()
        return parts
    }

    /** The raw text of `<name> = …` in an argument list, or null. */
    private fun namedArg(args: List<String>, pattern: Regex): String? =
        args.firstNotNullOfOrNull { pattern.find(it)?.groupValues?.get(1)?.trim() }

    private fun readProfile(source: String, call: MatchResult, label: String, problems: MutableList<String>): Profile? {
        val body = parenBody(source, call.range.last)
        if (body == null) {
            problems += "$label: an AddProfile( call could not be parsed"
            return null
        }
        val args = splitArgs(body)
        val name = namedArg(args, NAME_ARG)?.let { unquote(it) }
        if (name == null) {
            problems += "$label: an AddProfile row declares no literal name"
            return null
        }
        val rawBase = namedArg(args, BASE_URL_ARG)
        val declared = rawBase != null && rawBase != "null"
        val baseUrl = if (declared) unquote(rawBase.orEmpty()) else null
        if (declared && baseUrl == null) {
            problems += "$label [$name]: baseUrl is neither a string literal nor null " +
                "(${KotlinText.pyRepr(rawBase.orEmpty())}) — it cannot be joined to the example"
        }
        return Profile(Roster(label, name, baseUrl), namedArg(args, MODELS_ARG).orEmpty())
    }

    /** Appends every AddModel row of one profile; returns how many `AddModel(` calls it consumed. */
    private fun readModels(profile: Profile, windows: Map<String, Long>, problems: MutableList<String>): Int {
        var rows = 0
        for (call in ADD_MODEL_CALL.findAll(profile.models)) {
            rows += 1
            readModel(profile, call, windows, problems)
        }
        return rows
    }

    private fun readModel(
        profile: Profile,
        call: MatchResult,
        windows: Map<String, Long>,
        problems: MutableList<String>,
    ) {
        val roster = profile.roster
        val body = parenBody(profile.models, call.range.last)
        if (body == null) {
            problems += "${roster.where}: an AddModel( call could not be parsed"
            return
        }
        val positional = splitArgs(body).filter { !it.substringBefore('"').contains("=") }
        val modelId = positional.firstOrNull()?.let { unquote(it) }
        if (modelId == null) {
            problems += "${roster.where}: an AddModel row declares no literal id"
            return
        }
        val rawWindow = positional.getOrNull(WINDOW_ARGUMENT)?.trim()
        roster.models += Model(modelId, resolveWindow(roster, modelId, rawWindow, windows, problems))
    }

    /** A WINDOW_* constant from this same file, a bare literal, or null with the reason. */
    private fun resolveWindow(
        roster: Roster,
        modelId: String,
        rawWindow: String?,
        windows: Map<String, Long>,
        problems: MutableList<String>,
    ): Long? {
        if (rawWindow == null) {
            problems += "${roster.where}: $modelId declares no context window"
            return null
        }
        val named = windows[rawWindow]
        if (named != null) return named
        val literal = parseNumber(rawWindow)
        if (literal == null) {
            problems += "${roster.where}: $modelId's window ${KotlinText.pyRepr(rawWindow)} resolves to no " +
                "constant in this file and is not a literal — an unresolved window " +
                "cannot be compared, so this run is not trusted"
        }
        return literal
    }

    /** AddProfile rows out of AddProfileCatalog.kt, with WINDOW_* constants resolved. */
    fun parseKotlinCatalog(source: String, label: String): Parsed {
        val problems = mutableListOf<String>()
        val rosters = mutableListOf<Roster>()
        val windows = WINDOW_CONST.findAll(source)
            .mapNotNull { m -> m.groupValues[2].replace("_", "").toLongOrNull()?.let { m.groupValues[1] to it } }
            .toMap()
        var rowsParsed = 0
        for (call in ADD_PROFILE_CALL.findAll(source)) {
            val profile = readProfile(source, call, label, problems)
            if (profile != null) {
                rowsParsed += readModels(profile, windows, problems)
                rosters += profile.roster
            }
        }
        val rawRows = ADD_MODEL_CALL.findAll(stripKotlinComments(source)).count()
        if (rawRows != rowsParsed) {
            problems += "$label: parsed $rowsParsed AddModel rows but the file holds $rawRows " +
                "AddModel( calls — the parser and the source disagree, so no roster from this " +
                "run can be trusted"
        }
        if (windows.isEmpty()) {
            problems += "$label: no WINDOW_* constant declarations found — windows cannot be resolved"
        }
        return Parsed(rosters, problems)
    }

    /** DEFAULT_TOML's raw-string body out of TopologyLoader.kt, by its marker. */
    fun extractStarterToml(source: String, label: String): Starter {
        val anchor = source.indexOf(STARTER_MARKER)
        val open = if (anchor < 0) -1 else source.indexOf(RAW_QUOTE, anchor)
        val close = if (open < 0) -1 else source.indexOf(RAW_QUOTE, open + RAW_QUOTE.length)
        return when {
            anchor < 0 ->
                Starter(null, listOf("$label: $STARTER_MARKER not found — the starter roster's source is absent"))
            open < 0 ->
                Starter(null, listOf("$label: $STARTER_MARKER is not followed by a raw string literal"))
            close < 0 ->
                Starter(null, listOf("$label: $STARTER_MARKER's raw string is unterminated"))
            else -> Starter(source.substring(open + RAW_QUOTE.length, close), emptyList())
        }
    }
}

internal object ModelCatalogsSingleSource {
    const val EXAMPLE_REL = "app/src/main/resources/splice.example.toml"
    const val CATALOG_IN_APP = "splice/app/cli/add/AddProfileCatalog.kt"
    const val STARTER_IN_APP = "splice/app/daemon/TopologyLoader.kt"

    private const val PROVIDER_COLUMN = 12
    private const val ROWS_COLUMN = 2

    /** A file a violation names, and the file itself. */
    data class Surface(val rel: String, val file: File)

    /** The three files this law reads: the SOURCE and the two emitters. */
    data class Surfaces(val example: Surface, val catalog: Surface, val starter: Surface)

    /** The example indexed by its join key, and any ambiguity in it. */
    data class Index(val byBaseUrl: Map<String, ModelRosters.Roster>, val problems: List<String>)

    /** The example's rosters, the derived sources' rosters, and the untrusted-parse residue. */
    data class Loaded(
        val example: List<ModelRosters.Roster>,
        val derived: List<List<ModelRosters.Roster>>,
        val problems: List<String>,
    )

    /** An example provider seen from a derived roster: its name and its declared windows. */
    private data class Target(val provider: String, val windows: Map<String, Long?>)

    /** The three surfaces, resolved through the BUILD's map — `:app` wherever the build puts it. */
    fun surfaces(map: ProjectMap): Surfaces {
        val catalog = File(map.mainSources(":app"), CATALOG_IN_APP)
        val starter = File(map.mainSources(":app"), STARTER_IN_APP)
        return Surfaces(
            Surface(EXAMPLE_REL, File(map.root, EXAMPLE_REL)),
            Surface(KotlinText.rel(map, catalog), catalog),
            Surface(KotlinText.rel(map, starter), starter),
        )
    }

    // ── the join ──────────────────────────────────────────────────────────────────────────────

    fun indexByBaseUrl(rosters: List<ModelRosters.Roster>, label: String): Index {
        val problems = mutableListOf<String>()
        val index = linkedMapOf<String, ModelRosters.Roster>()
        for (roster in rosters) {
            val baseUrl = roster.baseUrl
            val prior = if (baseUrl == null) null else index[baseUrl]
            when {
                baseUrl == null -> Unit
                prior == null -> index[baseUrl] = roster
                else ->
                    problems += "$label: base_url $baseUrl is declared by BOTH " +
                        "[providers.${prior.provider}] and [providers.${roster.provider}] " +
                        "— the join key is ambiguous, so no comparison against this file is trustworthy"
            }
        }
        return Index(index, problems)
    }

    private fun exampleRosters(surface: Surface, problems: MutableList<String>): List<ModelRosters.Roster> {
        if (!surface.file.isFile) {
            problems += "${surface.rel}: missing — it IS the source, so its absence cannot pass"
            return emptyList()
        }
        val parsed = ModelRosters.parseTomlRosters(surface.file.readText(), surface.rel)
        problems += parsed.problems
        return parsed.rosters
    }

    private fun catalogRosters(surface: Surface, problems: MutableList<String>): List<ModelRosters.Roster> {
        if (!surface.file.isFile) {
            problems += "${surface.rel}: missing — a roster that cannot be read cannot be proven to agree"
            return emptyList()
        }
        val parsed = ModelRosters.parseKotlinCatalog(surface.file.readText(), surface.rel)
        problems += parsed.problems
        return parsed.rosters
    }

    private fun starterRosters(surface: Surface, problems: MutableList<String>): List<ModelRosters.Roster> {
        if (!surface.file.isFile) {
            problems += "${surface.rel}: missing — a roster that cannot be read cannot be proven to agree"
            return emptyList()
        }
        val starter = ModelRosters.extractStarterToml(surface.file.readText(), surface.rel)
        problems += starter.problems
        val text = starter.text ?: return emptyList()
        val parsed = ModelRosters.parseTomlRosters(text, "${surface.rel}:${ModelRosters.STARTER_MARKER}")
        problems += parsed.problems
        return parsed.rosters
    }

    fun loadSources(surfaces: Surfaces): Loaded {
        val problems = mutableListOf<String>()
        val example = exampleRosters(surfaces.example, problems)
        val catalog = catalogRosters(surfaces.catalog, problems)
        val starter = starterRosters(surfaces.starter, problems)
        return Loaded(example, listOf(catalog, starter), problems)
    }

    // ── the audit ─────────────────────────────────────────────────────────────────────────────

    private fun compare(
        roster: ModelRosters.Roster,
        model: ModelRosters.Model,
        target: Target,
        exampleRel: String,
    ): String? = when {
        !target.windows.containsKey(model.id) ->
            "${roster.where}: ${model.id} (context_window ${model.window}) is absent from " +
                "$exampleRel [providers.${target.provider}] — the example is the source, " +
                "so declare the row there or drop it here"
        target.windows[model.id] != model.window ->
            "${roster.where}: ${model.id} declares context_window ${model.window} but " +
                "$exampleRel [providers.${target.provider}] declares " +
                "${target.windows[model.id]} — the example is the source"
        else -> null
    }

    private fun targetOf(roster: ModelRosters.Roster): Target =
        Target(roster.provider, roster.models.associate { it.id to it.window })

    /** One derived roster against the example; returns the comparisons it performed. */
    private fun auditRoster(
        roster: ModelRosters.Roster,
        index: Map<String, ModelRosters.Roster>,
        exampleRel: String,
        problems: MutableList<String>,
    ): Int {
        val baseUrl = roster.baseUrl
        if (baseUrl == null) {
            if (roster.models.isNotEmpty()) {
                problems += "${roster.where}: declares ${roster.models.size} model row(s) but no base_url " +
                    "— it cannot be joined to the example, and an unjoinable roster is an " +
                    "absence, not a disposition"
            }
            return 0
        }
        val joined = index[baseUrl]
        if (joined == null) {
            problems += "${roster.where}: base_url $baseUrl matches no provider table in " +
                "$exampleRel — add the provider there, or point this roster at a declared one"
            return 0
        }
        val target = targetOf(joined)
        for (model in roster.models) {
            val problem = compare(roster, model, target, exampleRel)
            if (problem != null) problems += problem
        }
        return roster.models.size
    }

    private fun auditDerived(
        rosters: List<ModelRosters.Roster>,
        index: Map<String, ModelRosters.Roster>,
        exampleRel: String,
        problems: MutableList<String>,
    ): Int {
        if (rosters.none { it.models.isNotEmpty() }) {
            val label = rosters.firstOrNull()?.label ?: "a derived roster source"
            problems += "$label: parsed no roster carrying a model row — refusing to pass vacuously"
            return 0
        }
        return rosters.sumOf { auditRoster(it, index, exampleRel, problems) }
    }

    fun audit(surfaces: Surfaces): List<String> {
        val loaded = loadSources(surfaces)
        val problems = loaded.problems.toMutableList()
        if (loaded.example.none { it.models.isNotEmpty() }) {
            problems += "${surfaces.example.rel}: parsed no provider carrying a model row — refusing to compare " +
                "against an empty source, because a green over an empty denominator is what this " +
                "wall exists to prevent"
            return problems
        }
        val index = indexByBaseUrl(loaded.example, surfaces.example.rel)
        problems += index.problems
        val comparisons = loaded.derived.sumOf { auditDerived(it, index.byBaseUrl, surfaces.example.rel, problems) }
        if (comparisons == 0 && problems.isEmpty()) {
            problems += "compared 0 model rows across the derived rosters — refusing to pass vacuously"
        }
        return problems
    }

    // ── the census (the checker's `report`, line for line) ────────────────────────────────────

    private fun state(roster: ModelRosters.Roster, index: Map<String, ModelRosters.Roster>): String {
        val joined = index[roster.baseUrl.orEmpty()]
        val unjoinable = roster.baseUrl == null && roster.models.isEmpty()
        if (unjoinable) return "no-roster (no base_url, no models)"
        if (joined == null) return "NO DISPOSITION (base_url matches no example provider)"
        val target = targetOf(joined)
        val bad = roster.models.filter { compare(roster, it, target, "") != null }.map { it.id }
        return if (bad.isEmpty()) "agrees with [${joined.provider}]" else "DRIFT: ${bad.joinToString(", ")}"
    }

    private fun row(roster: ModelRosters.Roster, state: String): String =
        "    [${roster.provider.padEnd(PROVIDER_COLUMN)}] ${roster.models.size.toString().padStart(ROWS_COLUMN)} " +
            "rows  $state"

    fun census(surfaces: Surfaces): List<String> {
        val loaded = loadSources(surfaces)
        val index = indexByBaseUrl(loaded.example, surfaces.example.rel).byBaseUrl
        val lines = mutableListOf("model-catalogs-single-source: ${surfaces.example.rel} is the SOURCE")
        loaded.problems.forEach { lines += "  UNTRUSTED: $it" }
        for (roster in loaded.example) {
            lines += "  source   [${roster.provider.padEnd(PROVIDER_COLUMN)}] " +
                "${roster.models.size.toString().padStart(ROWS_COLUMN)} rows  ${roster.baseUrl}"
        }
        for (rosters in loaded.derived) {
            rosters.firstOrNull()?.let { lines += "  ${it.label}" }
            rosters.forEach { lines += row(it, state(it, index)) }
        }
        return lines
    }
}

class ModelCatalogsSingleSourceLawTest {
    private val map = ProjectMap.fromSystemProperties()

    @Test
    fun `every derived model row exists in the example with the same window - V4-98`() {
        val surfaces = ModelCatalogsSingleSource.surfaces(map)
        val loaded = ModelCatalogsSingleSource.loadSources(surfaces)
        assertTrue(loaded.example.count { it.models.isNotEmpty() } > 2) {
            "the example yielded ${loaded.example.size} provider(s) — the read is broken, and a law that " +
                "compares against nothing passes vacuously."
        }
        assertTrue(loaded.derived.all { rosters -> rosters.any { it.models.isNotEmpty() } }) {
            "a derived source yielded no roster carrying a model row: ${loaded.derived.map { it.size }}"
        }
        val problems = ModelCatalogsSingleSource.audit(surfaces)
        assertTrue(problems.isEmpty()) {
            problems.joinToString(separator = "\n  - ", prefix = "MODEL CATALOGS SINGLE SOURCE (V4-98) violated:\n  - ")
        }
    }

    /** The synthetic tree the red proof writes into: the SOURCE and the two emitters, each
     *  replaceable per arm, under the same relative paths the live tree uses. */
    private class Tree(val root: File) {
        private val synthetic = ProjectMap.parse(root, ":app=app;:core=core", setOf("build"))
        val surfaces = ModelCatalogsSingleSource.surfaces(synthetic)

        fun write(example: String = EXAMPLE_OK, catalog: String = CATALOG_OK, starter: String = STARTER_OK) {
            for ((surface, text) in listOf(
                surfaces.example to example,
                surfaces.catalog to catalog,
                surfaces.starter to starter,
            )) {
                surface.file.parentFile.mkdirs()
                surface.file.writeText(text)
            }
        }

        fun audit() = ModelCatalogsSingleSource.audit(surfaces)

        fun loaded() = ModelCatalogsSingleSource.loadSources(surfaces)
    }

    @Test
    fun `the compliant and boring trees are green - V4-98`(@TempDir root: File) {
        with(Tree(root)) {
            write()
            assertEquals(
                emptyList<String>(),
                audit(),
                "the compliant tree must be GREEN (alias join by base_url, example superset, null-baseUrl row)",
            )
            val loaded = loaded()
            assertEquals(emptyList<String>(), loaded.problems, "the compliant parse must be trusted")
            assertEquals(listOf("xai", "kimi"), loaded.example.map { it.provider })
            assertEquals(listOf(2, 1), loaded.example.map { it.models.size }, "extra_windows is not a model roster")
            assertEquals(listOf(3, 1), loaded.derived.map { it.size })

            // The BORING case: one provider, one model, nothing else — the tree that gets waved through.
            write(EXAMPLE_BORING, CATALOG_BORING, STARTER_BORING)
            assertEquals(emptyList<String>(), audit(), "the one-provider/one-model tree must be GREEN")
            assertEquals(listOf(1, 1), loaded().derived.map { it.first().models.size })
        }
    }

    /** The census is the surface an operator writes a roster against, so its SHAPE is part of the
     *  law: every derived roster carries a disposition on its own line, and `no-roster` is computed
     *  from the row rather than named in an exemption list. */
    @Test
    fun `the census gives every roster a disposition - V4-98`(@TempDir root: File) {
        with(Tree(root)) {
            write()
            assertEquals(
                listOf(
                    "model-catalogs-single-source: app/src/main/resources/splice.example.toml is the SOURCE",
                    "  source   [xai         ]  2 rows  https://api.x.ai/v1",
                    "  source   [kimi        ]  1 rows  https://api.kimi.com/coding",
                    "  app/src/main/kotlin/splice/app/cli/add/AddProfileCatalog.kt",
                    "    [grok        ]  1 rows  agrees with [xai]",
                    "    [kimi        ]  1 rows  agrees with [kimi]",
                    "    [api-key     ]  0 rows  no-roster (no base_url, no models)",
                    "  app/src/main/kotlin/splice/app/daemon/TopologyLoader.kt:DEFAULT_TOML",
                    "    [xai         ]  1 rows  agrees with [xai]",
                ),
                ModelCatalogsSingleSource.census(surfaces),
            )
        }
    }

    @Test
    fun `the law can actually fail - windows and ids in either emitter - V4-98`(@TempDir root: File) {
        with(Tree(root)) {
            write(catalog = CATALOG_OK.replace("WINDOW_500K = 500_000L", "WINDOW_500K = 400_000L"))
            assertHit(audit(), "grok-4.6", "400000", "500000") { "a catalog window that disagrees must be RED" }

            val mutated = CATALOG_OK.replace(
                "                AddModel(\"grok-4.6\", \"Grok 4.6\", WINDOW_500K),",
                "                AddModel(\"grok-4.6\", \"Grok 4.6\", WINDOW_500K),\n" +
                    "                AddModel(\"grok-fake-9\", \"Grok Fake 9\", WINDOW_1M),",
            )
            assertTrue(mutated != CATALOG_OK, "the catalog id mutation did not apply")
            write(catalog = mutated)
            assertHit(audit(), "grok-fake-9", "absent from") { "a synthetic catalog id must be RED BY NAME" }

            write(starter = STARTER_OK.replace("context_window = 500000", "context_window = 262144"))
            assertHit(audit(), "DEFAULT_TOML", "grok-4.6", "262144") { "a starter window that disagrees must be RED" }

            write(
                starter = STARTER_OK.replace(
                    "context_window = 500000\n$RAW",
                    "context_window = 500000\n[[providers.xai.models]]\nid = \"grok-fake-9\"\n" +
                        "context_window = 500000\n$RAW",
                ),
            )
            assertHit(audit(), "grok-fake-9", "absent from") { "a synthetic starter id must be RED BY NAME" }
        }
    }

    @Test
    fun `the law can actually fail - the join key and the vacuous cases - V4-98`(@TempDir root: File) {
        with(Tree(root)) {
            write(catalog = CATALOG_OK.replace("https://api.x.ai/v1", "https://api.xai.example/v1"))
            assertHit(audit(), "matches no provider table") { "an unjoinable base_url must be RED" }

            write(catalog = CATALOG_OK.replace(NULL_ROSTER, GREW_MODELS))
            assertHit(audit(), "no base_url") { "a null-baseUrl roster that grew models must be RED" }

            write(example = EXAMPLE_OK + "\n[providers.xai-clone]\nbase_url = \"https://api.x.ai/v1\"\n")
            assertHit(audit(), "ambiguous") { "a duplicated example base_url must be RED" }

            write(example = "[daemon]\ncontrol_port = 3096\n")
            assertHit(audit(), "refusing to compare against an empty source") { "an empty example must be RED" }

            write(catalog = "package splice.app.cli\n")
            assertHit(audit(), "refusing to pass vacuously") { "a catalog with no rosters must be RED" }

            write(starter = "package splice.app\npublic object TopologyLoader\n")
            assertHit(audit(), "DEFAULT_TOML not found") { "a missing starter marker must be RED" }
        }
    }

    @Test
    fun `the law can actually fail - the parser-drift guards - V4-98`(@TempDir root: File) {
        with(Tree(root)) {
            // TOML side: a models header the walker cannot attribute to any provider table.
            write(
                starter = STARTER_OK.replace(
                    "[providers.xai]\nbase_url",
                    "[[providers.xai.models]]\nid = \"orphan\"\ncontext_window = 1\n[providers.xai]\nbase_url",
                ),
            )
            val hits = audit()
            assertTrue(hits.any { it.contains("orphan") || it.contains("disagree") }) {
                "a model row the walker cannot attribute must be RED, got: ${KotlinText.pyReprList(hits)}"
            }

            // Kotlin side: an AddModel( call outside any AddProfile.
            write(catalog = CATALOG_OK + "\nprivate val orphan = AddModel(\"orphan\", \"Orphan\", WINDOW_1M)\n")
            assertHit(audit(), "the parser and the source disagree") {
                "an AddModel outside any AddProfile must be RED"
            }

            // A missing surface is an absence, never a skip.
            write()
            surfaces.example.file.delete()
            assertHit(audit(), "it IS the source, so its absence cannot pass") { "a missing example must be RED" }
        }
    }

    /** The other half of the checker's proof: the SHIPPED bytes, mutated. A fixture tree the law
     *  authored can drift away from the shape of the real files (a raw-string spelling, a trailing
     *  comment, an alias the fixtures never exercise) and every arm above would stay green while
     *  the law stopped reading the tree. */
    @Test
    fun `the law can actually fail - against the shipped files - V4-98`(@TempDir root: File) {
        val live = ModelCatalogsSingleSource.surfaces(map)
        val tree = Tree(root)
        val example = live.example.file.readText()
        val catalog = live.catalog.file.readText()
        val starter = live.starter.file.readText()
        tree.write(example, catalog, starter)
        assertEquals(emptyList<String>(), tree.audit(), "the shipped tree, copied, must be GREEN")

        val drifted = catalog.replace("WINDOW_200K = 200_000L", "WINDOW_200K = 199_999L")
        assertTrue(drifted != catalog, "MUTATION NOT APPLIED: the WINDOW_200K constant is gone")
        tree.write(example, drifted, starter)
        assertHit(tree.audit(), "declares context_window 199999") { "a real catalog window drift must be RED" }

        val slipped = starter.replace(LLAMA_ROW + "1048576", LLAMA_ROW + "131072")
        assertTrue(slipped != starter, "MUTATION NOT APPLIED: the llama-4-maverick starter row is gone")
        tree.write(example, catalog, slipped)
        assertHit(tree.audit(), "declares context_window 131072") { "a real DEFAULT_TOML window drift must be RED" }

        val shrunk = dropExampleRow(example)
        assertTrue(shrunk != example, "MUTATION NOT APPLIED: the z-ai/glm-5.3 example row is gone")
        tree.write(shrunk, catalog, starter)
        val hits = tree.audit()
        assertHit(hits, DELETED_ROW) { "a row deleted from the SOURCE must red the emitters that carry it" }
        assertEquals(2, hits.count { it.contains(DELETED_ROW) }, "one deleted SOURCE row reds BOTH emitters: $hits")
    }

    /** Drop the `[[providers.openrouter.models]]` row whose id is exactly `z-ai/glm-5.3`. */
    private fun dropExampleRow(example: String): String {
        val lines = example.split("\n")
        val out = mutableListOf<String>()
        var i = 0
        while (i < lines.size) {
            val header = lines[i].trim() == OPENROUTER_MODELS
            val isTarget = header && lines.getOrNull(i + 1)?.startsWith("id = \"z-ai/glm-5.3\"") == true
            if (isTarget) {
                i += 1
                while (i < lines.size && !lines[i].startsWith("[")) i += 1
            } else {
                out += lines[i]
                i += 1
            }
        }
        return out.joinToString("\n")
    }

    private companion object {
        const val RAW = "\"\"\""
        const val OPENROUTER_MODELS = "[[providers.openrouter.models]]"
        const val LLAMA_ROW = "id = \"meta-llama/llama-4-maverick\"\nlabel = \"Llama 4 Maverick\"\ncontext_window = "
        const val DELETED_ROW = "z-ai/glm-5.3 (context_window 1310720) is absent from"

        const val NULL_ROSTER = "            name = \"api-key\",\n            baseUrl = null,\n" +
            "            models = emptyList(),"
        const val GREW_MODELS = "            name = \"api-key\",\n            baseUrl = null,\n" +
            "            models = listOf(\n                AddModel(\"mystery\", \"Mystery\", WINDOW_1M),\n" +
            "            ),"

        const val EXAMPLE_OK = """[daemon]
control_port = 3096

[providers.xai]
dialect = "openai-responses"
base_url = "https://api.x.ai/v1"
[[providers.xai.models]]
id = "grok-4.6"
label = "Grok 4.6"
context_window = 500000        # a trailing comment the stripper must drop
[[providers.xai.models]]
id = "grok-4.3"
label = "Grok 4.3"
context_window = 1000000

[providers.kimi]
base_url = "https://api.kimi.com/coding"
[[providers.kimi.extra_windows]]
id = "k3"
context_window = 1000000
[[providers.kimi.models]]
id = "k3[1m]"
label = "Kimi K3 (1M)"
context_window = 1000000

[heads.grok]
provider = "xai"
context_window = 500000
"""

        const val CATALOG_OK = """package splice.app.cli

private const val WINDOW_500K = 500_000L
private const val WINDOW_1M = 1_000_000L

internal class AddProfileCatalog {
    val rows: List<AddProfile> = listOf(
        AddProfile(
            // The catalog calls xAI `grok`; the example calls it `xai`. base_url joins them.
            name = "grok",
            baseUrl = "https://api.x.ai/v1",
            models = listOf(
                AddModel("grok-4.6", "Grok 4.6", WINDOW_500K),
            ),
        ),
        AddProfile(
            name = "kimi",
            baseUrl = "https://api.kimi.com/coding",
            models = listOf(
                AddModel("k3[1m]", "Kimi K3 (1M)", WINDOW_1M, slots = listOf("opus")),
            ),
        ),
        AddProfile(
            name = "api-key",
            baseUrl = null,
            models = emptyList(),
        ),
    )
}
"""

        val STARTER_OK = """package splice.app

public object TopologyLoader {
    private const val DEFAULT_TOML = $RAW
[providers.xai]
base_url = "https://api.x.ai/v1"
[[providers.xai.models]]
id = "grok-4.6"
label = "Grok 4.6"
context_window = 500000
$RAW
}
"""

        const val EXAMPLE_BORING = """[providers.solo]
base_url = "https://example.invalid/v1"
[[providers.solo.models]]
id = "only-one"
label = "Only One"
context_window = 128000
"""

        const val CATALOG_BORING = """package splice.app.cli

private const val WINDOW_128K = 128_000L

internal class AddProfileCatalog {
    val rows: List<AddProfile> = listOf(
        AddProfile(name = "solo", baseUrl = "https://example.invalid/v1", models = listOf(
            AddModel("only-one", "Only One", WINDOW_128K),
        )),
    )
}
"""

        val STARTER_BORING = """package splice.app

public object TopologyLoader {
    private const val DEFAULT_TOML = $RAW
[providers.solo]
base_url = "https://example.invalid/v1"
[[providers.solo.models]]
id = "only-one"
context_window = 128000
$RAW
}
"""
    }
}
