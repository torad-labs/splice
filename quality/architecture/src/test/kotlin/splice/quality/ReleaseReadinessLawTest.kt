// NEW: the OSS readiness ladder's STATIC assertions (checks/oss/verify-OSS-A..M.sh, restructure
// PR 6, §6.3). The ladder's PROCESS legs did not become a law; each has a home:
//   verify-OSS-B  :daemon-control:test                    -> `check` runs every module's tests
//   verify-OSS-C  yaml.safe_load of every workflow         -> .github/workflows/workflow-lint.yml
//   verify-OSS-D  bash -n / shellcheck install.sh, the launcher rehearsal, the parsed release.yml
//                 steps, the SemVer and asset mutants, stage + accept, the dist/ enumeration
//                                                          -> `bun tools/release verify`
//   verify-OSS-I  bun audit --audit-level=critical, x3     -> `bun tools/gate audit`
//   verify-OSS-J  the JDK 21 resolver probe                -> tools/gate/test/jdk.test.ts
//                 --dependency-verification=strict         -> gradle/verification-metadata.xml, every build
//   verify-OSS-K  npm run test:hooks                       -> the ladder's hookTests leg
//
// THE CLASS. A public release that ships without something a public release must carry — a health
// file, a license notice, the fork record, a pinned action, a checksummed wrapper, a README that
// says what this is and is not, an installer that verifies what it downloads. Each was missing
// once, and each is cheap to lose again in a move; the ladder was the list of the ones that were.
//
// SCOPE. The checkout the build hands the laws (ProjectMap.root): repository files by path, git's
// index, and every Kotlin file under every mapped module's src/ (main, test and fixtures alike —
// the ladder's grep walked the whole tree).
//
// DENOMINATOR. Each rule names the file it reads, and a missing file is a violation, never a skip.
// The workflow rules read the .github/workflows listing (zero workflows is RED), the Kotlin rules
// read the module map (zero Kotlin files is RED), the tracked-file rules read `git ls-files`.
//
// PARSE. Substring and regex over whole files, case-insensitive where the shell used `grep -i`.
// Nothing is tokenized: these were the ladder's greps and they stay greps, with the file named.
// ONE RULE IS NOT A GREP. `|| true` is a swallowed failure on every line of install.sh but the two
// this repo has decided to allow, and those two are matched by their EXACT text. The shell's bare
// `grep -qE '\|\| true'` could not tell the best-effort `previous` link — whose result is read back
// on the very next line — from a swallowed download, and a narrowing that names the downloading
// commands trades that hole for its mirror: `mv "$JAR_TMP" "$SHARE_DIR/splice.jar" || true` carries
// none of those tokens, and the installer would print success over a stale jar. So does a wrapper
// call, and so does a `|| true` continued onto the next physical line. An exact-line list has
// neither hole, and rewording a listed line is re-deciding that swallow — a decision that belongs
// in a diff beside the list rather than inside a regex nobody re-reads (PR 6 review, F1).
//
// VIOLATIONS, failed BY NAME (rule id, then the file and what it lacks or still carries): every
// [ReleaseReadiness.Rule] below; one mutation per rule in the red proof.
package splice.quality

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.TimeUnit

private const val WORKFLOWS = ".github/workflows"
private const val INSTALL = "install.sh"
private const val README = "README.md"
private const val EXAMPLE_TOML = "app/src/main/resources/splice.example.toml"
private const val NOTICES = "THIRD_PARTY_NOTICES.md"
private const val RELEASE_WORKFLOW = ".github/workflows/release.yml"
private const val METADATA = "gradle/verification-metadata.xml"
private const val SETTINGS = ".claude/settings.json"
private const val PROVENANCE = "docs/PROVENANCE.md"
private const val GITIGNORE = ".gitignore"
private const val RUNBOOK = ".dev/release/history-rewrite-runbook.md"
private const val SECURITY_MD = ".github/SECURITY.md"
private const val BUG_TEMPLATE = ".github/ISSUE_TEMPLATE/bug.yml"
private const val DEPENDABOT = ".github/dependabot.yml"
private const val PACKAGE_JSON = "package.json"
private const val WRAPPER_PROPERTIES = "gradle/wrapper/gradle-wrapper.properties"
private const val FONTS = "console/src/shared/fonts"
private const val JDK_RESOLVER = "tools/gate/src/lib/jdk.ts"
private const val HOOK = "tools/gate/src/lib/hook.ts"
private const val LAUNCH_SERVICE = "daemon/control/src/main/kotlin/splice/control/LaunchService.kt"
private const val TOPOLOGY_LOADER = "app/src/main/kotlin/splice/app/daemon/TopologyLoader.kt"
private const val TESTNET = "daemon/head/src/testFixtures/kotlin/splice/head/TestNet.kt"
private const val ENCRYPTED_COT = "encrypted CoT"
private const val GATE_LADDER = "build-logic/src/main/kotlin/splice.gate-ladder.gradle.kts"
private const val INCLUDED_BUILD_TEST = "gradle.includedBuild(\"build-logic\").task(\":test\")"
private const val BUILD_LOGIC_TESTS = "build-logic/src/test/kotlin/"
private const val OR_TRUE = "|| true"

// The two swallowed failures install.sh is allowed, by their exact trimmed text (install.sh:370-383):
// each is the best-effort `previous` link, and each result is READ BACK on the next line, so the
// swallow is a decision someone wrote down rather than an omission. See the PARSE paragraph above.
private const val ALLOWED_LINK = "ln -sfn \"\$1\" \"\${SHARE_DIR}/releases/previous\" || true"
private const val ALLOWED_PREVIOUS = "link_previous \"\$(readlink \"\$CURRENT_LINK\")\" || true"
private val ALLOWED_OR_TRUE = listOf(ALLOWED_LINK, ALLOWED_PREVIOUS)
private val HEALTH_FILES = listOf(
    SECURITY_MD,
    ".github/CONTRIBUTING.md",
    ".github/CODE_OF_CONDUCT.md",
    ".github/PULL_REQUEST_TEMPLATE.md",
)

/** What the rules read: the checkout root, git's index, and every Kotlin file of the mapped modules. */
internal class ReleaseRepo(val root: File, val tracked: List<String>, val kotlinFiles: List<File>) {
    private fun text(rel: String): String? = File(root, rel).takeIf { it.isFile }?.readText()

    private fun missing(rel: String) = "$rel is missing"

    fun rel(file: File): String = file.relativeTo(root).invariantSeparatorsPath

    fun present(rel: String): String? = if (File(root, rel).exists()) null else missing(rel)

    fun contains(rel: String, needle: String, ignoreCase: Boolean = false): String? {
        val text = text(rel) ?: return missing(rel)
        return if (text.contains(needle, ignoreCase)) null else "$rel lacks '$needle'"
    }

    fun lacks(rel: String, needle: String, ignoreCase: Boolean = false): String? {
        val text = text(rel) ?: return missing(rel)
        return if (text.contains(needle, ignoreCase)) "$rel still contains '$needle'" else null
    }

    /** Lines of [rel] carrying [needle] whose exact trimmed text is not one of [allowed]. */
    fun onlyOn(rel: String, needle: String, allowed: List<String>, what: String): String? {
        val text = text(rel) ?: return missing(rel)
        val offenders = text.lines().mapIndexedNotNull { index, line ->
            if (needle in line && line.trim() !in allowed) "line ${index + 1}: ${line.trim()}" else null
        }
        if (offenders.isEmpty()) return null
        return "$rel carries $what outside the ${allowed.size} line(s) this repo has decided to allow " +
            "[${allowed.joinToString("; ")}] — ${offenders.joinToString("; ")}. Rewording an allowed line is " +
            "re-deciding that swallow: change the line and this list in the same commit."
    }

    fun matches(rel: String, pattern: Regex, what: String): String? {
        val text = text(rel) ?: return missing(rel)
        return if (pattern.containsMatchIn(text)) null else "$rel lacks $what"
    }

    fun untracked(prefix: String): String? =
        tracked.firstOrNull { it.startsWith(prefix) }?.let { "$it is tracked — remove it from the index" }

    /**
     * At least one tracked file under [prefix]. `build-logic-tested` proves the ladder NAMES the
     * included build's test task; nothing proved that task has anything to RUN, and an empty source
     * set is the exact shape that keeps a text check green while the census exclusion it earns goes
     * blind.
     */
    fun trackedUnder(prefix: String, what: String): String? =
        if (tracked.any { it.startsWith(prefix) }) null else "$prefix holds no tracked $what"

    fun trackedFile(rel: String): String? = if (rel in tracked) null else "$rel is not tracked"

    fun nonEmptyDir(rel: String, entry: Regex, what: String): String? {
        val names = File(root, rel).list()?.toList() ?: return missing(rel)
        return if (names.any { entry.containsMatchIn(it) }) null else "$rel holds no $what"
    }

    /** The first workflow [check] rejects; an EMPTY listing is a violation of its own. */
    fun eachWorkflow(check: (String, String) -> String?): String? {
        val files = File(root, WORKFLOWS).listFiles { file -> file.isFile && file.extension == "yml" }
            ?.sortedBy { it.name }.orEmpty()
        if (files.isEmpty()) return "no workflows under $WORKFLOWS — the denominator is empty"
        return files.firstNotNullOfOrNull { file -> check(rel(file), file.readText()) }
    }

    /** The first Kotlin file [check] rejects; an EMPTY module map is a violation of its own. */
    fun eachKotlin(check: (String, String) -> String?): String? {
        if (kotlinFiles.isEmpty()) return "no Kotlin files to scan — the module map is empty"
        return kotlinFiles.firstNotNullOfOrNull { file -> check(rel(file), file.readText()) }
    }
}

internal object ReleaseReadiness {
    data class Rule(val id: String, val check: (ReleaseRepo) -> String?)

    private const val GIT_SECONDS = 30L
    private val ENV_IGNORED = Regex("""^\.env""", RegexOption.MULTILINE)
    private val UNPINNED_ACTION = Regex("""uses: .*@v[0-9]+(?:[.][0-9]+)*[ \t]*$""", RegexOption.MULTILINE)
    private val FIXED_PORT = Regex("""= 39[0-9]{3}""")
    private val SLEEP_1100 = Regex("""Thread\.sleep\(1100\)""")
    private val FORK_RECORD = Regex("""UNRESOLVED|upstream""", RegexOption.IGNORE_CASE)
    private val FONT_LICENSE = Regex("""OFL|LICENSE""", RegexOption.IGNORE_CASE)
    private val CLAUDE_PRIVATE = listOf(
        ".claude/skills/",
        ".claude/ledger-diffs/",
        ".claude/agents/",
        ".claude/workflows/",
        ".claude/commands/",
        ".claude/mcp.json",
    )

    fun audit(repo: ReleaseRepo): List<String> =
        rules().mapNotNull { rule -> rule.check(repo)?.let { "${rule.id}: $it" } }

    fun rules(): List<Rule> =
        repositoryRules() + workflowRules() + installerRules() + readmeRules() + packagingRules() + kotlinRules()

    /** git's index at [root], NUL-separated so a path with a space survives; a git that cannot answer fails. */
    fun trackedFiles(root: File): List<String> {
        val process = ProcessBuilder("git", "ls-files", "-z").directory(root).redirectErrorStream(true).start()
        val output = process.inputStream.readBytes().decodeToString()
        check(process.waitFor(GIT_SECONDS, TimeUnit.SECONDS)) { "git ls-files did not finish under $root" }
        check(process.exitValue() == 0) { "git ls-files failed under $root: $output" }
        return output.split('\u0000').filter { it.isNotEmpty() }
    }

    /** Every .kt under every mapped module's src/, in path order. */
    fun kotlinFiles(map: ProjectMap): List<File> = map.modules.sorted().flatMap { module ->
        File(map.dir(module), "src").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .sortedBy { it.path }
            .toList()
    }

    private fun repositoryRules(): List<Rule> = listOf(
        Rule("tracked-capture-artifacts") { repo ->
            repo.tracked.firstOrNull { "capture/turn-" in it || "capture/ab-results" in it }
                ?.let { "$it is a captured turn — captures never ship" }
        },
        Rule("gitignore-env") { repo ->
            repo.matches(GITIGNORE, ENV_IGNORED, "a line ignoring .env files")
                ?: repo.contains(GITIGNORE, ".env.example")
        },
        Rule("history-rewrite-runbook") { repo -> repo.present(RUNBOOK) },
        Rule("launch-safe-by-default") { repo -> repo.contains(LAUNCH_SERVICE, "dangerously-skip-permissions") },
        Rule("health-files") { repo -> HEALTH_FILES.firstNotNullOfOrNull { repo.present(it) } },
        Rule("issue-templates") { repo -> repo.nonEmptyDir(".github/ISSUE_TEMPLATE", Regex("."), "issue template") },
        Rule("provenance") { repo ->
            repo.matches(PROVENANCE, FORK_RECORD, "the fork record (UNRESOLVED or upstream)")
        },
        Rule("claude-dir-untracked") { repo -> CLAUDE_PRIVATE.firstNotNullOfOrNull { repo.untracked(it) } },
        Rule("settings-hook") { repo ->
            repo.trackedFile(SETTINGS) ?: repo.contains(SETTINGS, "tools/gate rules --stdin pretooluse")
        },
        Rule("hook-tracked") { repo -> repo.trackedFile(HOOK) },
        Rule("tracked-agents") { repo -> repo.untracked("agents/crystallize-agent/") },
        // build-logic is EXCLUDED from the unmapped-source census (quality/architecture/build.gradle.kts)
        // because it is a separate Gradle build no project map can claim. That exclusion is earned by
        // this rule and by nothing else: build-logic's sources are governed because gateOfRecord runs
        // that build's own tests, and the day the dependency goes, the exclusion is a blind spot.
        Rule("build-logic-tested") { repo -> repo.contains(GATE_LADDER, INCLUDED_BUILD_TEST) },
        Rule("build-logic-has-tests") { repo -> repo.trackedUnder(BUILD_LOGIC_TESTS, "test source") },
    )

    private fun workflowRules(): List<Rule> = listOf(
        Rule("workflow-permissions") { repo ->
            repo.eachWorkflow { rel, text ->
                if ("permissions:" in text) null else "$rel declares no permissions: block"
            }
        },
        Rule("workflow-pinned-actions") { repo ->
            repo.eachWorkflow { rel, text ->
                UNPINNED_ACTION.find(text)?.let { "$rel uses an action by tag, not by SHA: ${it.value.trim()}" }
            }
        },
        Rule("release-draft-first") { repo -> repo.contains(RELEASE_WORKFLOW, "draft: true") },
        Rule("release-ships-licenses") { repo -> repo.contains(RELEASE_WORKFLOW, "THIRD_PARTY_LICENSES.txt") },
    )

    private fun installerRules(): List<Rule> = listOf(
        Rule("install-no-fork-url") { repo -> repo.lacks(INSTALL, "marcospaulo/splice") },
        Rule("install-downloads-from-releases") { repo -> repo.contains(INSTALL, "releases/download") },
        Rule("install-no-or-true") { repo ->
            repo.onlyOn(INSTALL, OR_TRUE, ALLOWED_OR_TRUE, "a swallowed failure (`|| true`)")
        },
        Rule("install-requires-gh") { repo -> repo.contains(INSTALL, "GitHub CLI (gh) is required") },
        Rule("install-attests-jar") { repo -> repo.contains(INSTALL, "verify_attestation \"\$JAR_TMP\" splice.jar") },
        Rule("install-attests-shim") { repo ->
            repo.contains(INSTALL, "verify_attestation \"\$SHIM_TMP\" splice-launch")
        },
    )

    private fun readmeRules(): List<Rule> = listOf(
        Rule("readme-no-encrypted-cot") { repo -> repo.lacks(README, ENCRYPTED_COT, ignoreCase = true) },
        Rule("readme-no-legacy-login") { repo -> repo.lacks(README, "bin/claudex login") },
        Rule("readme-not-affiliated") { repo -> repo.contains(README, "not affiliated", ignoreCase = true) },
        Rule("readme-names-shim") { repo -> repo.contains(README, "splice-launch") },
        Rule("readme-at-your-own-risk") { repo -> repo.contains(README, "at your own risk", ignoreCase = true) },
        Rule("readme-unofficial") { repo -> repo.contains(README, "unofficial", ignoreCase = true) },
        Rule("example-no-encrypted-cot") { repo -> repo.lacks(EXAMPLE_TOML, ENCRYPTED_COT, ignoreCase = true) },
        Rule("example-password-equivalent") { repo ->
            repo.contains(EXAMPLE_TOML, "password-equivalent", ignoreCase = true)
        },
        Rule("topology-loader-experimental") { repo ->
            repo.contains(TOPOLOGY_LOADER, "experimental", ignoreCase = true)
        },
    )

    private fun packagingRules(): List<Rule> = listOf(
        Rule("wrapper-checksum") { repo -> repo.contains(WRAPPER_PROPERTIES, "distributionSha256Sum") },
        Rule("third-party-notices") { repo ->
            repo.contains(NOTICES, "SIL Open Font License", ignoreCase = true)
                ?: repo.contains(NOTICES, "gradle wrapper", ignoreCase = true)
        },
        Rule("font-license") { repo -> repo.nonEmptyDir(FONTS, FONT_LICENSE, "OFL or LICENSE file") },
        Rule("dependabot") { repo -> repo.present(DEPENDABOT) },
        Rule("package-engines") { repo -> repo.contains(PACKAGE_JSON, "\"engines\"") },
        Rule("jdk-no-homebrew-path") { repo -> repo.lacks(JDK_RESOLVER, "/opt/homebrew/opt/openjdk@21") },
        Rule("verification-metadata") { repo ->
            repo.contains(METADATA, "<verify-metadata>true</verify-metadata>")
                ?: repo.contains(METADATA, "<sha256 value=")
        },
    )

    private fun kotlinRules(): List<Rule> = listOf(
        Rule("kotlin-no-fixed-ports") { repo ->
            repo.eachKotlin { rel, text ->
                if (FIXED_PORT.containsMatchIn(text)) "$rel hardcodes a 39xxx port — take one from TestNet" else null
            }
        },
        Rule("testnet-fixture") { repo -> repo.present(TESTNET) },
        Rule("kotlin-no-sleep-1100") { repo ->
            repo.eachKotlin { rel, text ->
                if (SLEEP_1100.containsMatchIn(text)) "$rel sleeps 1100 ms — wait on the condition instead" else null
            }
        },
    )
}

private const val MIN_TRACKED = 100
private const val MIN_KOTLIN = 100
private const val CI_WORKFLOW = ".github/workflows/ci.yml"
private const val INSTALL_RELEASE = "RELEASE_BASE=releases/download\n"
private const val INSTALL_GH = "echo 'GitHub CLI (gh) is required'\n"
private const val INSTALL_JAR = "verify_attestation \"\$JAR_TMP\" splice.jar\n"
private const val INSTALL_SHIM = "verify_attestation \"\$SHIM_TMP\" splice-launch\n"
private const val INSTALL_PREVIOUS = "  $ALLOWED_LINK\n  $ALLOWED_PREVIOUS\n"

// The Kotlin fixtures are assembled at runtime so this file, which the live rules also scan, does
// not trip its own port and sleep rules.
private const val A_FIXED_PORT = 39_100
private const val A_SLEEP_MS = 1100

/** A synthetic checkout: every file it writes is "tracked", every .kt it writes is a Kotlin source. */
private class Tree(val root: File) {
    val files = mutableListOf<String>()

    fun file(rel: String, text: String = "") {
        File(root, rel).apply {
            parentFile.mkdirs()
            writeText(text)
        }
        if (rel !in files) files += rel
    }

    fun delete(rel: String) {
        File(root, rel).delete()
        files.remove(rel)
    }

    fun append(rel: String, text: String) = File(root, rel).appendText(text)

    fun repo() = ReleaseRepo(root, files.sorted(), files.filter { it.endsWith(".kt") }.map { File(root, it) })

    fun compliant() {
        file(GITIGNORE, ".env\n.env.example\n")
        file(RUNBOOK, "# runbook\n")
        file(LAUNCH_SERVICE, "// dangerously-skip-permissions is opt-in\n")
        file(CI_WORKFLOW, "permissions: {}\nsteps:\n  - uses: actions/checkout@3d3c42e5aac5 # v7.0.1\n")
        file(RELEASE_WORKFLOW, "permissions: {}\n  draft: true\n  files: dist/THIRD_PARTY_LICENSES.txt\n")
        // The compliant installer CARRIES both allowed swallows: an allowlist never exercised on the
        // green side proves only that the file had nothing to match.
        file(INSTALL, INSTALL_RELEASE + INSTALL_GH + INSTALL_JAR + INSTALL_SHIM + INSTALL_PREVIOUS)
        file(GATE_LADDER, "gateOfRecord { dependsOn($INCLUDED_BUILD_TEST) }\n")
        file("${BUILD_LOGIC_TESTS}splice/discovery/TestDiscoveryTest.kt", "class TestDiscoveryTest\n")
        file(
            README,
            "splice is not affiliated with anyone. Each head runs the splice-launch shim.\n" +
                "The unofficial routes are used at your own risk.\n",
        )
        file(EXAMPLE_TOML, "# every key here is password-equivalent\n")
        file(TOPOLOGY_LOADER, "// Experimental examples remain opt-in\n")
        packaging()
    }

    private fun packaging() {
        HEALTH_FILES.forEach { file(it, "# $it\n") }
        file(BUG_TEMPLATE, "name: bug\n")
        file(NOTICES, "SIL Open Font License\nGradle wrapper\n")
        file("$FONTS/OFL.txt", "OFL\n")
        file(DEPENDABOT, "version: 2\n")
        file(PACKAGE_JSON, "{\"engines\":{\"node\":\">=24\"}}\n")
        file(JDK_RESOLVER, "export const jdk = 21;\n")
        file(WRAPPER_PROPERTIES, "distributionSha256Sum=0\n")
        file(METADATA, "<verify-metadata>true</verify-metadata>\n<sha256 value=\"0\"/>\n")
        file(SETTINGS, "{\"hooks\":\"bun tools/gate rules --stdin pretooluse\"}\n")
        file(HOOK, "export {};\n")
        file(PROVENANCE, "# Provenance\nForked from the upstream proxy.\n")
        file(TESTNET, "object TestNet\n")
    }
}

private data class Mutation(val name: String, val rule: String, val detail: String, val mutate: Tree.() -> Unit)

private fun repositoryMutations(): List<Mutation> = listOf(
    Mutation("a tracked capture artifact", "tracked-capture-artifacts", "capture/turn-1.json") {
        files += "capture/turn-1.json"
    },
    Mutation(".gitignore without .env", "gitignore-env", GITIGNORE) { file(GITIGNORE, "node_modules\n") },
    Mutation(".gitignore without .env.example", "gitignore-env", ".env.example") { file(GITIGNORE, ".env\n") },
    Mutation("no history-rewrite runbook", "history-rewrite-runbook", "history-rewrite-runbook.md") { delete(RUNBOOK) },
    Mutation("LaunchService without the opt-in flag", "launch-safe-by-default", "LaunchService.kt") {
        file(LAUNCH_SERVICE, "// nothing to see\n")
    },
    Mutation("a missing health file", "health-files", SECURITY_MD) { delete(SECURITY_MD) },
    Mutation("no issue templates", "issue-templates", ".github/ISSUE_TEMPLATE") { delete(BUG_TEMPLATE) },
    Mutation("PROVENANCE without the fork record", "provenance", PROVENANCE) { file(PROVENANCE, "# Provenance\n") },
    Mutation("a tracked .claude/skills file", "claude-dir-untracked", ".claude/skills/") {
        files += ".claude/skills/x.md"
    },
    Mutation("settings.json not tracked", "settings-hook", SETTINGS) { files.remove(SETTINGS) },
    Mutation("settings.json without the hook line", "settings-hook", SETTINGS) { file(SETTINGS, "{}\n") },
    Mutation("hook.ts not tracked", "hook-tracked", HOOK) { files.remove(HOOK) },
    Mutation("a tracked crystallize agent", "tracked-agents", "agents/crystallize-agent/") {
        files += "agents/crystallize-agent/agent.md"
    },
    Mutation("build-logic's own tests out of the gate", "build-logic-tested", INCLUDED_BUILD_TEST) {
        file(GATE_LADDER, "// a ladder that no longer runs the included build's tests\n")
    },
    // The ladder can name the included build's :test while that build has NOTHING to run, because
    // `build-logic-tested` reads the ladder's TEXT and never the test set it invokes.
    Mutation("build-logic's test sources deleted", "build-logic-has-tests", BUILD_LOGIC_TESTS) {
        files.removeAll { it.startsWith(BUILD_LOGIC_TESTS) }
    },
    // THE MISSING-FILE BRANCH of each reader (PR 6 review). `contains`, `lacks`, `matches` and
    // `onlyOn` all answer `missing(rel)` when the file is not there, and nothing proved it: a rule
    // whose subject is DELETED must fail, never pass for want of anything to read.
    Mutation("no README at all", "readme-not-affiliated", "is missing") { delete(README) },
    Mutation("no README to read for encrypted CoT", "readme-no-encrypted-cot", "is missing") { delete(README) },
    Mutation("no PROVENANCE at all", "provenance", "is missing") { delete(PROVENANCE) },
    Mutation("no install.sh at all", "install-no-or-true", "is missing") { delete(INSTALL) },
)

private fun workflowMutations(): List<Mutation> = listOf(
    Mutation("a workflow without permissions", "workflow-permissions", "ci.yml") { file(CI_WORKFLOW, "steps: []\n") },
    Mutation("no workflows at all", "workflow-permissions", "denominator is empty") {
        delete(CI_WORKFLOW)
        delete(RELEASE_WORKFLOW)
    },
    Mutation("a workflow using an action by tag", "workflow-pinned-actions", "checkout@v4") {
        file(CI_WORKFLOW, "permissions: {}\nsteps:\n  - uses: actions/checkout@v4\n")
    },
    Mutation("a dotted tag is a tag too", "workflow-pinned-actions", "checkout@v4.2.1") {
        file(CI_WORKFLOW, "permissions: {}\nsteps:\n  - uses: actions/checkout@v4.2.1  \n")
    },
    Mutation("release.yml publishing directly", "release-draft-first", "draft: true") {
        file(RELEASE_WORKFLOW, "permissions: {}\n  files: dist/THIRD_PARTY_LICENSES.txt\n")
    },
    Mutation("release.yml not shipping the licenses", "release-ships-licenses", "THIRD_PARTY_LICENSES.txt") {
        file(RELEASE_WORKFLOW, "permissions: {}\n  draft: true\n")
    },
)

private fun installerMutations(): List<Mutation> = listOf(
    Mutation("install.sh naming the fork", "install-no-fork-url", "marcospaulo/splice") {
        append(INSTALL, "REPO=marcospaulo/splice\n")
    },
    Mutation("install.sh not downloading from releases", "install-downloads-from-releases", "releases/download") {
        file(INSTALL, INSTALL_GH + INSTALL_JAR + INSTALL_SHIM)
    },
    // Each of these was ACCEPTED by the narrowed regex this list replaced, and each is a swallow the
    // shell's bare grep rejected: a commit carrying none of the downloading commands, a download
    // through a wrapper, and a `|| true` continued onto the next physical line.
    Mutation("install.sh swallowing the commit of a downloaded jar", "install-no-or-true", "mv ") {
        append(INSTALL, "  mv \"\$JAR_TMP\" \"\$SHARE_DIR/splice.jar\" || true\n")
    },
    Mutation("install.sh swallowing a download through a wrapper", "install-no-or-true", "download ") {
        append(INSTALL, "  download \"\$JAR_URL\" \"\$JAR_TMP\" || true\n")
    },
    Mutation("install.sh swallowing on a continuation line", "install-no-or-true", OR_TRUE) {
        append(INSTALL, "  verify_attestation \"\$JAR_TMP\" splice.jar \\\n    || true\n")
    },
    Mutation("install.sh not requiring gh", "install-requires-gh", "GitHub CLI (gh) is required") {
        file(INSTALL, INSTALL_RELEASE + INSTALL_JAR + INSTALL_SHIM)
    },
    Mutation("install.sh not attesting the jar", "install-attests-jar", "splice.jar") {
        file(INSTALL, INSTALL_RELEASE + INSTALL_GH + INSTALL_SHIM)
    },
    Mutation("install.sh not attesting the shim", "install-attests-shim", "splice-launch") {
        file(INSTALL, INSTALL_RELEASE + INSTALL_GH + INSTALL_JAR)
    },
)

private fun readmeMutations(): List<Mutation> = listOf(
    Mutation("README mentioning encrypted CoT", "readme-no-encrypted-cot", ENCRYPTED_COT) {
        append(README, "we replay Encrypted CoT\n")
    },
    Mutation("README with the legacy login", "readme-no-legacy-login", "bin/claudex login") {
        append(README, "run bin/claudex login\n")
    },
    Mutation("README without the affiliation notice", "readme-not-affiliated", "not affiliated") {
        file(README, "splice-launch, unofficial, at your own risk\n")
    },
    Mutation("README not naming the shim", "readme-names-shim", "splice-launch") {
        file(README, "not affiliated, unofficial, at your own risk\n")
    },
    Mutation("README without the risk notice", "readme-at-your-own-risk", "at your own risk") {
        file(README, "not affiliated, unofficial, splice-launch\n")
    },
    Mutation("README without unofficial", "readme-unofficial", "unofficial") {
        file(README, "not affiliated, splice-launch, at your own risk\n")
    },
    Mutation("the example config mentioning encrypted CoT", "example-no-encrypted-cot", ENCRYPTED_COT) {
        append(EXAMPLE_TOML, "# encrypted CoT replay\n")
    },
    Mutation("the example config without password-equivalent", "example-password-equivalent", "password-equivalent") {
        file(EXAMPLE_TOML, "# keys\n")
    },
    Mutation("TopologyLoader without experimental", "topology-loader-experimental", "TopologyLoader.kt") {
        file(TOPOLOGY_LOADER, "// plain\n")
    },
)

private fun packagingMutations(): List<Mutation> = listOf(
    Mutation("a wrapper without a checksum", "wrapper-checksum", "gradle-wrapper.properties") {
        file(WRAPPER_PROPERTIES, "distributionUrl=x\n")
    },
    Mutation("notices without the font license", "third-party-notices", "SIL Open Font License") {
        file(NOTICES, "Gradle wrapper\n")
    },
    Mutation("notices without the wrapper", "third-party-notices", "gradle wrapper") {
        file(NOTICES, "SIL Open Font License\n")
    },
    Mutation("fonts without a license file", "font-license", FONTS) {
        delete("$FONTS/OFL.txt")
        file("$FONTS/Inter.woff2", "font")
    },
    Mutation("no dependabot", "dependabot", "dependabot.yml") { delete(DEPENDABOT) },
    Mutation("package.json without engines", "package-engines", PACKAGE_JSON) { file(PACKAGE_JSON, "{}\n") },
    Mutation("jdk.ts with the homebrew path", "jdk-no-homebrew-path", "/opt/homebrew/opt/openjdk@21") {
        append(JDK_RESOLVER, "const brew = \"/opt/homebrew/opt/openjdk@21\";\n")
    },
    Mutation("verification metadata off", "verification-metadata", "<verify-metadata>true") {
        file(METADATA, "<verify-metadata>false</verify-metadata>\n<sha256 value=\"0\"/>\n")
    },
    Mutation("verification metadata without a sha256", "verification-metadata", "<sha256 value=") {
        file(METADATA, "<verify-metadata>true</verify-metadata>\n")
    },
)

private fun kotlinMutations(): List<Mutation> = listOf(
    Mutation("a Kotlin file hardcoding a 39xxx port", "kotlin-no-fixed-ports", "core/src/test/kotlin/PortTest.kt") {
        file("core/src/test/kotlin/PortTest.kt", "val port = $A_FIXED_PORT\n")
    },
    Mutation("no TestNet fixture", "testnet-fixture", "TestNet.kt") { delete(TESTNET) },
    Mutation("a Kotlin file sleeping 1100 ms", "kotlin-no-sleep-1100", "core/src/test/kotlin/SleepTest.kt") {
        file("core/src/test/kotlin/SleepTest.kt", "fun wait() = Thread.sleep($A_SLEEP_MS)\n")
    },
    // The Kotlin set has to be EMPTY for this to prove anything, and a hand-listed three went stale
    // the first time a .kt joined the compliant tree. Derive the deletion from the tree itself.
    Mutation("no Kotlin files at all", "kotlin-no-fixed-ports", "module map is empty") {
        files.filter { it.endsWith(".kt") }.forEach { delete(it) }
    },
)

class ReleaseReadinessLawTest {
    private val map = ProjectMap.fromSystemProperties()

    @Test
    fun `live - the checkout carries everything a public release must`() {
        val repo = ReleaseRepo(map.root, ReleaseReadiness.trackedFiles(map.root), ReleaseReadiness.kotlinFiles(map))
        assertTrue(repo.tracked.size > MIN_TRACKED) {
            "git ls-files answered ${repo.tracked.size} paths — the census did not run"
        }
        assertTrue(repo.kotlinFiles.size > MIN_KOTLIN) {
            "${repo.kotlinFiles.size} Kotlin files — the module map is not the tree"
        }
        val problems = ReleaseReadiness.audit(repo)
        assertTrue(problems.isEmpty()) { "release readiness:\n" + problems.joinToString("\n") }
    }

    @Test
    fun `a compliant tree is green, and every rule ran over it`(@TempDir root: File) {
        val tree = Tree(root)
        tree.compliant()
        assertEquals(emptyList<String>(), ReleaseReadiness.audit(tree.repo()))
        val covered = mutations().map { it.rule }.toSet()
        assertEquals(
            ReleaseReadiness.rules().map { it.id }.toSet(),
            covered,
            "every rule has a mutation proving it can fail",
        )
    }

    @TestFactory
    fun `the law can actually fail - one mutation per rule`(@TempDir root: File): List<DynamicTest> =
        mutations().mapIndexed { index, mutation ->
            DynamicTest.dynamicTest(mutation.name) {
                val tree = Tree(File(root, index.toString()))
                tree.compliant()
                mutation.mutate(tree)
                assertHit(ReleaseReadiness.audit(tree.repo()), mutation.rule, mutation.detail) {
                    "${mutation.name} must be RED under ${mutation.rule}, naming ${mutation.detail}"
                }
            }
        }

    private fun mutations(): List<Mutation> =
        repositoryMutations() + workflowMutations() + installerMutations() + readmeMutations() +
            packagingMutations() + kotlinMutations()
}
