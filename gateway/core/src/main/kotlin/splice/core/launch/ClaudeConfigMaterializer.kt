// PORT-OF: server/launcher/prepare-config.mjs @ pre-public-port-baseline as a TRANSLITERATION (high blast radius —
// mutates the operator's ~/.claude* state), generalized to the per-head share/isolate policy.
// Invariants preserved EXACTLY:
//   - isolated CLAUDE_CONFIG_DIR (default ~/.claude-<head>); refuse to write outside it;
//   - SHARED items symlink into ~/.claude/<item>; a real file where a symlink belongs is replaced,
//     but a real DIRECTORY the operator made is NEVER deleted (one exception: sessions/ is
//     machine-generated, so SessionRegistryLink migrates its entries into the global registry and
//     replaces the dir with the link — cross-head session visibility);
//   - settings.json is ALWAYS a real merged file (never a symlink through which we'd clobber the
//     operator's global): global settings + availableModels allowlist + enforceAvailableModels +
//     preserved model choice (when still allowed) + the statusline command. A pre-existing symlink
//     there is NOT pre-deleted (DR-11 redo): the saved model is read only from a REAL file, and the
//     atomic temp + ATOMIC_MOVE write replaces the symlink NAME without following it — one step, no
//     missing-file window, the operator's global untouched;
//   - .claude.json: additionalModelOptionsCache = the catalog, MCP inherit from ~/.claude.json,
//     portKeys inherit (only when absent locally), hasCompletedOnboarding = true.
// isolate list overrides share per item (an isolated item gets a seeded copy, not a link).
package splice.core.launch

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import splice.core.util.Cancellables
import splice.core.util.DaemonLog
import splice.core.util.JsonScalars
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import splice.core.util.SecureFile
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.UUID
import kotlin.io.path.isDirectory
import kotlin.io.path.isSymbolicLink

// DTOs live in ClaudeMaterializeTypes.kt; Keys + sharedLinkItems + portKeys live in
// ClaudeConfigKeys.kt (concentration, 2026-08-19). Same-package FQCNs are unchanged.

/** Creates one symbolic link. A seam because the swap's whole safety property — that a failure
 *  NEVER destroys the operator's pre-existing file — is only testable on the production path if the
 *  create can be made to fail on demand (no temp filesystem denies createSymbolicLink), and that
 *  failure is exactly the ENOSPC/LSM-EPERM case DR-11 was opened for. Public because it is a
 *  default param of a public constructor and the no-secondary-constructor law leaves one init
 *  path: an internal type here would trip "public constructor exposes internal parameter type". */
public fun interface SymlinkOp {
    public operator fun invoke(link: Path, target: Path)
}

public class ClaudeConfigMaterializer(
    private val home: Path,
    private val log: LogSink = LogSink(DaemonLog::write),
    private val symlink: SymlinkOp = SymlinkOp { link, target -> Files.createSymbolicLink(link, target) },
) {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }
    private val sessionRegistry = SessionRegistryLink()
    private val jsonReads = JsonStateReads(json, log)

    /** Materialize a head's isolated CLAUDE_CONFIG_DIR from [spec]. */
    public fun materialize(spec: MaterializeSpec): MaterializeResult {
        requireIsolatedDir(spec.configDir)
        // Validate every ABORTING source BEFORE any mutation (DR-11 redo, codex ordering catch).
        // The local .claude.json is the one strict read — an unparseable one fails the launch — and
        // it used to run at the END of writeClaudeJson, after linkShared, the hooks, and
        // settings.json had already changed operator-visible state, leaving a half-built config on
        // abort. Reading it first means a corrupt local aborts with nothing yet touched. (`strict`
        // treats an absent file as a fresh head; only unparseable throws.)
        val localClaudeJson = jsonReads.strict(spec.configDir.resolve(Keys.CLAUDE_JSON))
        // DR-105: the OTHER aborting source. readSettingsModelBase throws on a present-but-
        // unreadable real settings.json, and it used to run inside writeSettings — after
        // linkShared, the one-way sessions migration and the hook writes — leaving exactly the
        // half-built config dir the paragraph above promises cannot happen. Safe to hoist:
        // linkShared explicitly skips settings.json (merged, never linked), so nothing between
        // here and writeSettings can change what this read observes.
        val existingSettings = readSettingsModelBase(spec.configDir.resolve(Keys.SETTINGS))
        Files.createDirectories(spec.configDir)
        linkShared(spec.configDir, spec.policy)
        val hookAdditions = LoginInterception.concat(
            LoginInterception.wire(
                spec.configDir,
                spec.loginCommand,
                spec.signInLabel,
                globalCommands = if (shares(spec.policy, Keys.COMMANDS)) globalDir().resolve(Keys.COMMANDS) else null,
                viaBrowser = spec.signInViaBrowser,
                tokenCapture = spec.tokenCapture,
                loginOutcomeFile = spec.loginOutcomeFile,
            ),
            if (spec.advertiseKeySetup && spec.tokenCapture != null) {
                LoginInterception.keySetupAdvertiser(spec.configDir, spec.tokenCapture, spec.loginCommand)
            } else {
                emptyMap()
            },
        )
        writeSettings(spec, hookAdditions, existingSettings)
        val mcpCount = writeClaudeJson(
            spec.configDir,
            spec.modelOptionsCache,
            shareMcp = shares(spec.policy, Keys.MCPS),
            local = localClaudeJson,
        )
        return MaterializeResult(spec.configDir, spec.availableModelIds.size, mcpCount)
    }

    // Guard the operator's REAL global config: the dir must look like an isolated .claude* dir AND
    // must not resolve to ~/.claude itself. `contains("claude")` let ~/.claude (and
    // ~/Documents/claude-notes, /tmp/claude) through — this closes both.
    private fun requireIsolatedDir(configDir: Path) {
        val target = configDir.toAbsolutePath().normalize()
        // DR-102: normalize() is textual and never resolves symlinks — a config_dir SYMLINK
        // (~/.claude-x -> ~/.claude) passed this guard and every mutation materialize performs
        // next landed in the operator's REAL global dir. Identity is judged on real paths; the
        // .claude* NAME is still judged on the spelling the operator chose.
        val isolated = target.fileName.toString().startsWith(Keys.CLAUDE_DIR) &&
            realOf(target) != realOf(globalDir().toAbsolutePath().normalize())
        require(isolated) {
            "refuse to materialize into '$configDir' — must be an isolated .claude* dir, not the global ~/.claude"
        }
    }

    /** Filesystem identity for a path that may not exist yet: the real path when it does, else the
     *  nearest existing ancestor's real path re-joined with the missing tail. NoSuchFileException
     *  is the only absence signal (class law); any other IO failure fails the guard closed. */
    private fun realOf(path: Path): Path {
        val parent = path.parent ?: return path
        return try {
            path.toRealPath()
        } catch (ignored: java.nio.file.NoSuchFileException) {
            realOf(parent).resolve(path.fileName)
        }
    }

    private fun globalDir() = home.resolve(Keys.CLAUDE)

    /**
     * Does the policy share [item]? Alias-aware, so the friendly config vocabulary (settings,
     * mcps, claude_md) matches the on-disk item names (settings.json, mcps, CLAUDE.md). isolate
     * wins over share for any alias.
     */
    private fun shares(policy: ClaudePolicy, item: String): Boolean {
        val aliases = when (item.lowercase()) {
            Keys.SETTINGS -> setOf(Keys.SETTINGS, "settings")
            "claude.md" -> setOf(Keys.CLAUDE_MD, "claude_md", "claude.md", "claudemd")
            Keys.MCPS -> setOf(Keys.MCPS, "mcp")
            else -> setOf(item)
        }
        return aliases.any { it in policy.share } && aliases.none { it in policy.isolate }
    }

    // settings is merged (not linked); mcps arrive via .claude.json. Everything else that the
    // policy shares is symlinked from the operator's global dir.
    private fun linkShared(configDir: Path, policy: ClaudePolicy) {
        // settings is merged (not linked) and mcps arrive via .claude.json, so both are skipped here.
        for (item in sharedLinkItems) {
            val linkable = item != Keys.SETTINGS && item != Keys.MCPS && shares(policy, item)
            if (!linkable) continue
            if (item == Keys.SESSIONS) {
                // The peer registry migrates rather than links: see SessionRegistryLink's header.
                // link() logs its own declines; this catches what it THROWS mid-flight (DR-39).
                Cancellables.runCatchingCancellable {
                    sessionRegistry.link(globalDir().resolve(item), configDir.resolve(item))
                }.exceptionOrNull()?.let { cause ->
                    log("[materialize] sessions registry NOT linked into $configDir (${cause.message})\n")
                }
            } else {
                linkOneShared(configDir, item)
            }
        }
    }

    private fun linkOneShared(configDir: Path, item: String) {
        val src = globalDir().resolve(item)
        // DR-39 redo 3 (codex): `exists(src, NOFOLLOW)` was an absence PRE-gate — it reads false
        // through an untraversable global .claude, so every shared layer skipped SILENTLY and the
        // head launched without the operator's hooks/agents/skills, no line anywhere. A NOFOLLOW
        // stat of the entry itself is the honest probe: only NoSuchFileException proves the
        // optional share absent (NOFOLLOW never follows, so no dangling ambiguity to split). A
        // denied parent is loud; a dangling entry is loud too and NOT mirrored — linking it would
        // ship a broken layer with no cause named.
        val probeFailure = Cancellables.runCatchingCancellable {
            Files.getLastModifiedTime(src, NOFOLLOW_LINKS)
            if (!Files.exists(src)) {
                throw IOException("global entry is a link whose target is missing or unreachable")
            }
        }.exceptionOrNull()
        if (probeFailure is NoSuchFileException) return
        if (probeFailure != null) {
            log(
                "[materialize] shared '$item' NOT linked into $configDir (${probeFailure.message}) — " +
                    "this head launches without the operator's $item\n",
            )
            return
        }
        // Best-effort, and now truly so: the swap below is atomic, so an I/O failure at any point
        // leaves whatever is already on disk (DR-11a — the old delete-then-create pair made this
        // comment a lie: a create failing after the delete had destroyed the operator's file).
        // Best-effort is no longer SILENT (DR-39): a head quietly launching without the operator's
        // hooks/agents/skills layer sent every symptom hunt to the wrong place — each undone share
        // now names itself once per materialize. The decline (a real dir where the link would go)
        // is detected here so it can be EXEMPTED for commands, whose real-dir-ness is the designed
        // steady state (login.md lives inside it; LoginInterception re-links shared entries
        // item-by-item — see this file's header).
        val dst = configDir.resolve(item)
        if (Files.isDirectory(dst, NOFOLLOW_LINKS) && !dst.isSymbolicLink()) {
            if (item != Keys.COMMANDS) {
                log(
                    "[materialize] shared '$item' kept as this head's own real directory at $dst — " +
                        "the operator's global copy is not linked\n",
                )
            }
            return
        }
        Cancellables.runCatchingCancellable { replaceWithSymlink(src, dst) }
            .exceptionOrNull()
            ?.let { cause ->
                log(
                    "[materialize] shared '$item' NOT linked into $configDir (${cause.message}) — " +
                        "this head launches without the operator's $item\n",
                )
            }
    }

    private fun replaceWithSymlink(src: Path, dst: Path) {
        if (Files.isDirectory(dst, NOFOLLOW_LINKS) && !dst.isSymbolicLink()) {
            return // belt for the caller's decline check: never delete a real dir the operator made
        }
        // Build the replacement beside dst, then swap in ONE rename (DR-11a). ENOSPC still spends
        // an inode and an LSM can deny symlink creation — with delete-then-create either lost the
        // original forever; here a staging failure leaves dst untouched and the move is atomic
        // over a pre-existing file or symlink alike.
        val staged = dst.resolveSibling(".${dst.fileName}.splice-link-${UUID.randomUUID()}")
        symlink(staged, src)
        try {
            Files.move(staged, dst, REPLACE_EXISTING, ATOMIC_MOVE)
        } finally {
            // DR-104: the staged leftover is a courtesy — a cleanup throw must never REPLACE the
            // in-flight outcome (the decline log would name the wrong cause, and a success would
            // read as failed). Same rule as LoginInterception's writeHookScript teardown.
            Cancellables.discard(
                Cancellables.runCatchingCleanup { Files.deleteIfExists(staged) },
                "staged-link cleanup — the move outcome must stand",
            )
        }
    }

    // [existing] is the DR-105 preflight read from materialize() — the one aborting read this
    // function used to perform itself, after the mutations it must now precede.
    private fun writeSettings(
        spec: MaterializeSpec,
        hookAdditions: Map<String, List<JsonObject>>,
        existing: JsonObject,
    ) {
        val allow = spec.availableModelIds
        val dst = spec.configDir.resolve(Keys.SETTINGS)
        val global = if (shares(spec.policy, Keys.SETTINGS)) {
            jsonReads.tolerant(globalDir().resolve(Keys.SETTINGS))
        } else {
            EMPTY_JSON
        }
        // DR-136: the LAST aborting read, and the one the DR-105 hoist missed — the hoist moved
        // readSettingsModelBase up but left this EXTRACTION here, after linkShared and the one-way
        // sessions migration. `?.jsonPrimitive?.content` throws on a non-primitive, so a head
        // settings.json whose "model" is an object parses cleanly, escapes the corrupt-content
        // rebuild, and then throws from here — the half-built config dir the header forbids.
        // JsonScalars is the sanctioned throw-free read (and filters JsonNull, which this chain
        // used to leak as the literal string "null"; both shapes now fall back to the default).
        val savedModel = JsonScalars.str(existing[Keys.MODEL])
        val model = if (savedModel != null && savedModel in allow) savedModel else spec.defaultModel
        val hooks = LoginInterception.mergeInto(global[Keys.HOOKS], hookAdditions)
        val merged = buildJsonObject {
            global.forEach { (k, v) -> if (isCarriedGlobalKey(k)) put(k, v) }
            putJsonArray(Keys.AVAILABLE_MODELS) { allow.forEach { add(it) } }
            put("enforceAvailableModels", true)
            put(Keys.MODEL, model)
            put(Keys.STATUS_LINE, statusLineBlock(spec.statuslineCommand))
            if (hooks != null) put(Keys.HOOKS, hooks)
        }
        // The one atomic-write primitive (DR-11b): a LIVE Claude Code re-reads this file, and the
        // old truncate-then-write let it observe a torn settings.json mid-launch.
        SecureFile.writeAtomic0600(dst, json.encodeToString(JsonObject.serializer(), merged) + "\n")
    }

    // The saved model choice, read only from a REAL settings.json (a symlink points at the
    // operator's global, whose model is not this head's to preserve). NO pre-delete of a symlink
    // here (DR-11 redo, codex pre-delete-window catch): the old Files.delete opened a window where
    // a concurrent reader saw a missing settings.json and, if a later step failed, the original
    // link was gone. writeSettings finishes through SecureFile.writeAtomic0600, whose temp +
    // ATOMIC_MOVE replaces the symlink NAME without following it — the operator's global is never
    // clobbered and the swap is a single atomic step with no missing-file window.
    private fun readSettingsModelBase(dst: Path): JsonObject {
        // A symlink stays the deliberate EMPTY case (the operator's global model is not this
        // head's to preserve). Everything else direct-reads (DR-64): proven absence or corrupt
        // CONTENT rebuilds (the materializer owns this file), but indeterminate ACCESS to a real
        // settings file aborts — rebuilding over it silently reset the operator's saved model.
        if (dst.isSymbolicLink()) return EMPTY_JSON
        return Cancellables.runCatchingCancellable { json.parseToJsonElement(Files.readString(dst)).jsonObject }
            .getOrElse { failure ->
                val genuinelyAbsent = failure is NoSuchFileException && !Files.exists(dst, NOFOLLOW_LINKS)
                if (failure is IOException && !genuinelyAbsent) {
                    throw IOException(
                        "$dst unreadable (${SafeFailureText.render(failure)}) — " +
                            "refusing to rebuild the head settings over it",
                    )
                }
                EMPTY_JSON
            }
    }

    private fun isCarriedGlobalKey(key: String): Boolean =
        key != Keys.MODEL && key != Keys.AVAILABLE_MODELS && key != Keys.STATUS_LINE && key != Keys.HOOKS

    private fun statusLineBlock(command: String): JsonObject = buildJsonObject {
        put("type", "command")
        put("command", command)
        put("padding", 0)
    }

    private fun writeClaudeJson(
        configDir: Path,
        modelOptionsCache: JsonElement,
        shareMcp: Boolean,
        local: JsonObject,
    ): Int {
        val statePath = configDir.resolve(Keys.CLAUDE_JSON)
        val global = jsonReads.tolerant(home.resolve(Keys.CLAUDE_JSON))
        // [local] is the strict read of statePath, hoisted to materialize() and validated BEFORE any
        // mutation (DR-11 redo). global stays a tolerant SOURCE read here — it never aborts.
        var mcpCount = 0
        val next = buildJsonObject {
            local.forEach { (k, v) -> put(k, v) }
            put("additionalModelOptionsCache", modelOptionsCache)
            val globalMcp = (global[Keys.MCP_SERVERS] as? JsonObject)?.takeIf { shareMcp }
            if (globalMcp != null) {
                mcpCount = globalMcp.size
                put(Keys.MCP_SERVERS, globalMcp)
            }
            for (k in portKeys) {
                // Read once into a local: the map is looked up twice in the old shape and the
                // second read had to be asserted non-null because the compiler cannot know a
                // JsonObject returns the same value twice. One read, no assertion, same result.
                val inherited = global[k]
                if (inherited != null && local[k] == null) put(k, inherited)
            }
            put(Keys.CUSTOM_API_KEY_RESPONSES, customApiKeyResponses(local))
            put(Keys.ONBOARDING, true)
        }
        // Atomic for the same reason as writeSettings (DR-11b): terminal B materializing while a
        // session in terminal A reads .claude.json must never expose a truncated file.
        SecureFile.writeAtomic0600(statePath, json.encodeToString(JsonObject.serializer(), next) + "\n")
        return mcpCount
    }

    // Preserve any approved custom keys but CLEAR rejected — a stale rejection would otherwise
    // dead-end the proxy's auth token at Claude Code's custom-key approval prompt.
    private fun customApiKeyResponses(local: JsonObject): JsonObject = buildJsonObject {
        put("approved", (local[Keys.CUSTOM_API_KEY_RESPONSES] as? JsonObject)?.get("approved") ?: buildJsonArray {})
        put("rejected", buildJsonArray {})
    }
}

// The two strictness modes for reading .claude* JSON state (DR-11c; a collaborator so the
// materializer stays under the type-size wall). TOLERANT is for merge SOURCES that are never
// rewritten (global settings, global .claude.json) — degrading those to empty loses an inherit,
// not operator state. STRICT is for the file a rewrite is about to REPLACE (the
// KeyStore.entriesStrict doctrine): ABSENT = a fresh head, safe to seed; UNPARSEABLE = unknown
// state — abort the materialize (and with it the launch) rather than rebuild a five-key file over
// every local key Claude Code owns, the approved customApiKeyResponses included.
private class JsonStateReads(private val json: Json, private val log: LogSink) {

    // Sweep 2026-08-31 (absence class): both modes carried an exists/symlink pre-gate. Tolerant
    // read an unreadable global as silent EMPTY (the head lost every carried key with no trace)
    // and skipped readable dotfiles symlinks; strict read a symlinked local as "fresh head" and
    // seeded OVER the operator's link. Now the read is attempted first and only a proven absence
    // (NoSuch + no NOFOLLOW entry) is quiet-empty.
    fun tolerant(path: Path): JsonObject = Cancellables
        .runCatchingCancellable { json.parseToJsonElement(Files.readString(path)).jsonObject }
        .getOrElse { failure ->
            val genuinelyAbsent = failure is NoSuchFileException && !Files.exists(path, NOFOLLOW_LINKS)
            if (!genuinelyAbsent) {
                log(
                    "[materialize] $path unreadable (${SafeFailureText.render(failure)}) — " +
                        "global state NOT inherited by this head\n",
                )
            }
            EMPTY_JSON
        }

    fun strict(path: Path): JsonObject {
        // A symlink here is the KeyStore.entriesStrict doctrine: the entry EXISTS (dangling
        // included), and the atomic rewrite would replace the operator's link with a plain file.
        if (path.isSymbolicLink()) {
            throw IOException(
                "$path is a symlink — refusing to replace the operator's link with a materialized file; " +
                    "remove the link or point the head at a real file",
            )
        }
        return Cancellables.runCatchingCancellable { json.parseToJsonElement(Files.readString(path)).jsonObject }
            .getOrElse { failure ->
                val genuinelyAbsent = failure is NoSuchFileException && !Files.exists(path, NOFOLLOW_LINKS)
                if (!genuinelyAbsent) {
                    throw IOException(
                        "$path unreadable (${SafeFailureText.render(failure)}) — " +
                            "refusing to rewrite it; fix or remove the file",
                    )
                }
                EMPTY_JSON
            }
    }
}

// Companion dissolved to file scope (Kotlin style law, 2026-08-16 — HD-M8), same name, same value.
// FILE SCOPE ON PURPOSE: one immutable empty object shared by every read path, as the companion's
// single instance already was — a per-instance field would allocate one per materializer.
private val EMPTY_JSON = JsonObject(emptyMap())
