// splice setup on the prompt toolkit. Headless topology is the
// captured first-run no-plan oracle; declining confirm writes nothing. Extra heads tick
// from AddProfiles and install through AddCommand.
package splice.app.cli.setup

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.configuration.add.AddProfiles
import splice.configuration.add.DaemonRestart
import splice.core.config.UserHome
import splice.core.terminal.CliPalette
import splice.core.terminal.ColorDepth
import splice.core.topology.AuthConfig
import splice.core.topology.ClaudeWrapperConfig
import splice.core.topology.Dialect
import splice.core.topology.HeadConfig
import splice.core.topology.ProviderConfig
import splice.core.topology.Topology
import splice.core.util.EnvReader
import splice.terminal.ConfirmPrompt
import splice.terminal.MultiSelectOutcome
import splice.terminal.Spinner
import splice.terminal.WizardFrame
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path

class SetupCommandTest {

    @Test
    fun `headless setup writes the captured no-plan topology`(@TempDir home: Path) {
        val topology = withHome(home) {
            seedShim(home)
            runBlocking {
                SetupCommand(
                    loginHead = NO_REAL_LOGIN,
                    effects = SetupEffects(EnvReader(System::getenv), add = NOTHING_TICKED),
                ).setup()
            }
            Files.readString(home.resolve(".config").resolve("splice").resolve("splice.toml"))
        }
        assertEquals(HEADLESS_ORACLE, topology)
    }

    @Test
    fun `declining confirm leaves the topology file absent`(@TempDir home: Path) {
        val path = home.resolve(".config").resolve("splice").resolve("splice.toml")
        var asked = 0
        withHome(home) {
            seedShim(home)
            runBlocking {
                SetupCommand(
                    prompts = SetupPrompts(
                        frame = WizardFrame(
                            out = StringBuilder(),
                            ask = ConfirmPrompt { question, _ ->
                                if (question == "Install now?") asked += 1
                                false
                            },
                        ),
                        spinner = Spinner(StringBuilder(), tty = false),
                    ),
                    loginHead = NO_REAL_LOGIN,
                    effects = SetupEffects(EnvReader(System::getenv)),
                    detect = { emptyFacts() },
                ).setup()
            }
        }
        assertEquals(1, asked, "Install now? must be asked once")
        assertFalse(Files.exists(path), "declined confirm must write nothing")
    }

    // Nov 23, 2026: a setup that connects no plan is a whole setup, since `claude` runs on splice's door with nothing
    // signed in. It used to close on "Not set up yet." and skip the doctor; this test pinned that and was rewritten.
    @Test
    fun `the closing block still names every affordance, by command`(@TempDir home: Path) {
        var checkups = 0
        val log = captureStdout {
            withHome(home) {
                seedShim(home)
                runBlocking {
                    SetupCommand(
                        loginHead = NO_REAL_LOGIN,
                        effects = SetupEffects(
                            EnvReader(System::getenv),
                            add = object : ProfileAdd {
                                override suspend fun invoke(name: String): Boolean = error("nothing is ticked")

                                override fun checkup(env: EnvReader) {
                                    checkups++
                                }
                            },
                        ),
                    ).setup()
                }
            }
        }
        // Pinned by COMMAND, not by label. The 2026-09-22 redesign dropped the Launch/Dashboard/
        // Status/Checkup labels — the operator reads the command itself now — and the old arm failed
        // on the wording while the real regression it should catch is an AFFORDANCE going missing.
        assertTrue("splice status" in log, log)
        assertTrue("splice doctor" in log, log)
        assertTrue("splice setup --update" in log, log)
        assertFalse("splice dashboard" in log, "the console was removed on Oct 7, 2026: $log")
        assertTrue("anything wrong prints its fix" in log)
        val hero = log.lines().first { it.startsWith("      ") && it.isNotBlank() }.trim()
        assertEquals("splice launch claude", hero, "with no plan, the next thing to type is claude on the door: $log")
        assertFalse("Not set up yet." in log, "a planless setup on the door is set up: $log")
        assertEquals(1, checkups, "the doctor runs once with no plan connected too")
    }

    // "splice launch claude-desktop" is 28 characters; the fixed 18-wide column ran it into its own description.
    @Test
    fun `every command in the closing block lines up its description`() {
        val log = captureStdout {
            SetupSignIn(NO_REAL_LOGIN, EnvReader { null }, CliPalette(ColorDepth.NONE)).printNextSteps(Topology())
        }
        val rows = listOf(
            "splice launch claude-desktop" to "Claude Desktop side-by-side on Splice",
            "splice console" to "accounts, models and usage, in a browser",
            "splice doctor" to "anything wrong prints its fix",
        )
        val columns = rows.map { (command, purpose) ->
            val line = log.lines().single { it.trim().startsWith(command) && purpose in it }
            assertTrue(line.indexOf(purpose) > line.indexOf(command) + command.length, "no gap after $command: $line")
            line.indexOf(purpose)
        }
        assertEquals(1, columns.toSet().size, "the descriptions start in one column: $log")
    }

    // Nov 23, 2026: setup retires each plan's command (custom head commands are deprecated), so the hero is the launch
    // command and each ready plan is named by what to type in `claude`; this test asserted a head's command as the
    // hero and `<command> login` as the fix, and was rewritten with the change.
    @Test
    fun `the close never calls a head ready that has no credential`(@TempDir tmp: Path) {
        // Readiness is the predicate `splice status` uses: an api-key head with no key is not ready, and the fix for
        // any head missing a credential is `splice login <key>`, which runs LoginCommand for every kind.
        val tokens = Files.writeString(tmp.resolve("kimi-auth.json"), "{}")
        val topology = Topology(
            providers = mapOf(
                "or" to ProviderConfig(
                    Dialect.OPENAI_CHAT,
                    "https://example.invalid",
                    AuthConfig("api-key", env = CLOSE_KEY),
                ),
                "kimi" to ProviderConfig(
                    Dialect.ANTHROPIC_PASSTHROUGH,
                    "https://example.invalid",
                    AuthConfig("kimi-oauth", file = tokens.toString()),
                ),
            ),
            // The keyless head FIRST, so a readiness check that misses it makes it the hero.
            heads = linkedMapOf(
                "openrouter" to closeHead("or", "claude-or"),
                "kimi" to closeHead("kimi", "claude-kimi"),
            ),
        )
        val log = captureStdout {
            SetupSignIn(NO_REAL_LOGIN, EnvReader { null }, CliPalette(ColorDepth.NONE)).printNextSteps(topology)
        }
        val hero = log.lines().first { it.startsWith("      ") && it.isNotBlank() }.trim()
        assertEquals("splice launch claude", hero, "the hero is the command that runs claude on splice: $log")
        val inClaude = log.lines().single { "in claude" in it }
        assertTrue("/model test--test-model" in inClaude, "a ready plan is named by its selector: $log")
        assertTrue("splice login openrouter" in log, "the keyless head must name the command that fixes it: $log")
        assertEquals(1, Regex("/model ").findAll(inClaude).count(), "a keyless head is not ready: $log")
    }

    private fun closeHead(provider: String, command: String) = HeadConfig(
        provider = provider,
        port = 3099,
        discoveryPrefix = "test--",
        pinnedModel = "test-model",
        claude = ClaudeWrapperConfig(command = command),
    )

    // Operator ruling, 2026-09-26: no disclaimer anywhere. The walk says where the sign-in lands, nothing more.
    @Test
    fun `sign-in walk names its own credential file and prints no disclaimer`(@TempDir home: Path) {
        val log = captureStdout {
            withHome(home) {
                seedShim(home)
                seedOauthTopology(home)
                runBlocking {
                    SetupCommand(
                        prompts = SetupPrompts(
                            spinner = Spinner(tty = false),
                        ),
                        loginHead = { true },
                        effects = SetupEffects(EnvReader(System::getenv), add = NOTHING_TICKED),
                    ).setup()
                }
            }
        }
        assertFalse(log.contains("unofficial", ignoreCase = true), log)
        assertFalse(log.contains("own risk", ignoreCase = true), log)
        assertTrue("own credential file" in log, log)
    }

    @Test
    fun `declining confirm leaves a seeded topology byte-identical`(@TempDir home: Path) {
        withHome(home) {
            seedShim(home)
            seedOauthTopology(home)
            val path = home.resolve(".config").resolve("splice").resolve("splice.toml")
            val before = Files.readString(path)
            runBlocking {
                SetupCommand(
                    prompts = SetupPrompts(
                        frame = WizardFrame(
                            out = StringBuilder(),
                            ask = ConfirmPrompt { _, _ -> false },
                        ),
                        spinner = Spinner(StringBuilder(), tty = false),
                    ),
                    loginHead = NO_REAL_LOGIN,
                    effects = SetupEffects(EnvReader(System::getenv)),
                    detect = { emptyFacts() },
                ).setup()
            }
            assertEquals(before, Files.readString(path))
        }
    }

    @Test
    fun `leaving the list of plans with Escape ends setup and writes no topology`(@TempDir home: Path) {
        val path = home.resolve(".config").resolve("splice").resolve("splice.toml")
        val ok = withHome(home) {
            seedShim(home)
            runBlocking {
                SetupCommand(
                    prompts = SetupPrompts(
                        frame = WizardFrame(out = StringBuilder(), ask = ConfirmPrompt { _, d -> d }),
                        pickHeads = HeadPicker { _, _ -> MultiSelectOutcome.Cancelled },
                        spinner = Spinner(StringBuilder(), tty = false),
                    ),
                    loginHead = NO_REAL_LOGIN,
                    effects = SetupEffects(EnvReader(System::getenv)),
                    detect = { emptyFacts() },
                ).setup()
            }
        }
        assertTrue(ok)
        assertFalse(Files.exists(path))
    }

    /** What setup does about `claude` (side-by-side-launchers): it never wraps default `claude` and tells the user
     *  about `splice launch claude` and `splice launch claude-desktop`, optionally installing `claude-splice` and the
     *  OS Applications folder launcher when ticked in interactive mode. */
    @Nested
    inner class SideBySideSetup {
        @Test
        fun `setup never wraps default claude and names splice launch commands`(@TempDir home: Path) {
            val chrome = StringBuilder()
            val log = captureStdout {
                withHome(home) {
                    seedShim(home)
                    runBlocking {
                        SetupCommand(
                            prompts = SetupPrompts(
                                frame = WizardFrame(out = chrome, ask = ConfirmPrompt { _, d -> d }),
                                spinner = Spinner(tty = false),
                            ),
                            loginHead = NO_REAL_LOGIN,
                            detect = { emptyFacts() },
                            effects = SetupEffects(
                                EnvReader(System::getenv),
                                add = ProfileAdd { error("nothing is ticked") },
                            ),
                        ).setup()
                    }
                }
            }
            val combined = chrome.toString() + "\n" + log
            assertFalse(
                Files.exists(home.resolve(".local").resolve("bin").resolve("claude")),
                "setup must never wrap default claude",
            )
            assertTrue("splice launch claude" in combined, combined)
            assertTrue("splice launch claude-desktop" in combined, combined)
        }

        @Test
        fun `interactive setup installs ticked claude-splice and OS app launcher`(@TempDir home: Path) {
            val chrome = StringBuilder()
            withHome(home) {
                seedShim(home)
                runBlocking {
                    SetupCommand(
                        prompts = SetupPrompts(
                            frame = WizardFrame(out = chrome, ask = ConfirmPrompt { _, d -> d }),
                            hasConsole = { true },
                            pickHeads = HeadPicker { _, _ -> MultiSelectOutcome.Chosen(emptyList()) },
                            pickLaunchers = HeadPicker { _, _ ->
                                MultiSelectOutcome.Chosen(listOf(OPT_CLI, OPT_DESKTOP_APP))
                            },
                            spinner = Spinner(tty = false),
                        ),
                        loginHead = NO_REAL_LOGIN,
                        detect = { emptyFacts() },
                        env = EnvReader { null },
                        localModel = SetupLocalModel(
                            SetupPrompts(),
                            EnvReader { null },
                            DaemonRestart { true },
                            host = RigHost(gpu = GpuProbe { emptyList() }),
                        ),
                        effects = SetupEffects(
                            EnvReader(System::getenv),
                            add = ProfileAdd { error("nothing is ticked") },
                            launchers = SetupLaunchers(EnvReader { null }, osName = "Linux"),
                        ),
                    ).setup()
                }
            }
            val cliLink = home.resolve(".local").resolve("bin").resolve("claude-splice")
            val desktopFile = home.resolve(".local").resolve("share").resolve("applications")
                .resolve("ai.torad.claude-splice.desktop")
            assertTrue(Files.isSymbolicLink(cliLink), "expected claude-splice symlink at $cliLink")
            assertTrue(Files.isRegularFile(desktopFile), "expected desktop file at $desktopFile")
            assertTrue(Files.readString(desktopFile).contains("launch claude-desktop"))
        }
    }

    @Nested
    inner class Heads {
        @Test
        fun `every AddProfiles entry is offered or excluded by name`(@TempDir home: Path) {
            val offered = mutableListOf<String>()
            val chrome = StringBuilder()
            withHome(home) {
                seedShim(home)
                runBlocking {
                    SetupCommand(
                        prompts = SetupPrompts(
                            frame = WizardFrame(out = chrome, ask = ConfirmPrompt { _, d -> d }),
                            pickHeads = HeadPicker { options, _ ->
                                offered += options.map { it.value }
                                MultiSelectOutcome.Chosen(emptyList())
                            },
                        ),
                        loginHead = NO_REAL_LOGIN,
                        detect = { emptyFacts() },
                        effects = SetupEffects(
                            EnvReader(System::getenv),
                            add = ProfileAdd { error("empty tick must not add") },
                        ),
                    ).setup()
                }
            }
            val log = chrome.toString()
            val catalog = AddProfiles().catalog().map { it.name }.toSet()
            val named = catalog.filter { name -> "splice add $name" in log }.toSet()
            assertEquals(catalog, offered.toSet() + named, "catalog=$catalog offered=$offered named=$named log=$log")
            assertTrue("api-key" in named, log)
            assertFalse("api-key" in offered)
            assertTrue("local" in named, "a local model needs a name, URL and model before adding: $log")
            assertFalse("local" in offered, "the wizard cannot tick an incomplete local profile")
            // door-default (docs/specs/door-default.md, decision 8): the Claude plan left the tick list, since `claude`
            // runs on the door; it is named by its command instead, as a deprecated head. This replaces V4-175's
            // "claude is tickable" arm, which pinned the old behaviour.
            assertTrue("claude" in named, log)
            assertFalse("claude" in offered, log)
        }

        @Test
        fun `headless setup adds no heads and restarts zero times`(@TempDir home: Path) {
            val added = mutableListOf<String>()
            var restarts = 0
            withHome(home) {
                seedShim(home)
                runBlocking {
                    SetupCommand(
                        loginHead = NO_REAL_LOGIN,
                        effects = SetupEffects(
                            EnvReader(System::getenv),
                            add = ProfileAdd { name ->
                                added += name
                                true
                            },
                            restart = DaemonRestart {
                                restarts += 1
                                true
                            },
                        ),
                    ).setup()
                }
            }
            assertEquals(emptyList<String>(), added)
            assertEquals(0, restarts)
        }

        @Test
        fun `one failing head does not abort the others`(@TempDir home: Path) {
            val added = mutableListOf<String>()
            withHome(home) {
                seedShim(home)
                runBlocking {
                    SetupCommand(
                        prompts = SetupPrompts(
                            pickHeads = HeadPicker { _, _ ->
                                MultiSelectOutcome.Chosen(listOf("codex", "grok", "kimi"))
                            },
                        ),
                        loginHead = NO_REAL_LOGIN,
                        detect = { emptyFacts() },
                        effects = SetupEffects(
                            EnvReader(System::getenv),
                            add = ProfileAdd { name ->
                                added += name
                                name != "grok"
                            },
                            restart = DaemonRestart { true },
                        ),
                    ).setup()
                }
            }
            assertEquals(listOf("codex", "grok", "kimi"), added)
        }

        // A plan's own command is a deprecated custom head, which doctor warned about right after setup made it.
        @Test
        fun `setup retires the command of each plan it connected, and only those`(@TempDir home: Path) {
            val retired = mutableListOf<String>()
            withHome(home) {
                seedShim(home)
                runBlocking {
                    SetupCommand(
                        prompts = SetupPrompts(
                            pickHeads = HeadPicker { _, _ -> MultiSelectOutcome.Chosen(listOf("codex", "kimi")) },
                        ),
                        loginHead = NO_REAL_LOGIN,
                        detect = { emptyFacts() },
                        effects = SetupEffects(
                            EnvReader(System::getenv),
                            add = object : ProfileAdd {
                                override suspend fun invoke(name: String): Boolean = name == "codex"

                                override fun retire(keys: List<String>, env: EnvReader) {
                                    retired += keys
                                }
                            },
                            restart = DaemonRestart { true },
                        ),
                    ).setup()
                }
            }
            assertEquals(listOf(AddProfiles().find("codex")?.head?.key), retired, "only the plan that landed")
        }

        @Test
        fun `daemon restarts at most once after adding heads`(@TempDir home: Path) {
            var restarts = 0
            withHome(home) {
                seedShim(home)
                runBlocking {
                    SetupCommand(
                        prompts = SetupPrompts(
                            pickHeads = HeadPicker { _, _ -> MultiSelectOutcome.Chosen(listOf("codex", "kimi")) },
                        ),
                        loginHead = NO_REAL_LOGIN,
                        detect = { emptyFacts() },
                        effects = SetupEffects(
                            EnvReader(System::getenv),
                            add = ProfileAdd { true },
                            restart = DaemonRestart {
                                restarts += 1
                                true
                            },
                        ),
                    ).setup()
                }
            }
            assertEquals(1, restarts)
        }

        @Test
        fun `Summary names every ticked head`(@TempDir home: Path) {
            val chrome = StringBuilder()
            withHome(home) {
                seedShim(home)
                runBlocking {
                    SetupCommand(
                        prompts = SetupPrompts(
                            frame = WizardFrame(out = chrome, ask = ConfirmPrompt { _, _ -> false }),
                            pickHeads = HeadPicker { _, _ -> MultiSelectOutcome.Chosen(listOf("muse", "deepseek")) },
                        ),
                        loginHead = NO_REAL_LOGIN,
                        effects = SetupEffects(EnvReader(System::getenv)),
                        detect = { emptyFacts() },
                    ).setup()
                }
            }
            val text = chrome.toString()
            assertTrue("Plans to connect: muse, deepseek" in text, text)
        }

        @Test
        fun `already-installed head is shown and not tickable`(@TempDir home: Path) {
            val offered = mutableListOf<String>()
            val chrome = StringBuilder()
            withHome(home) {
                seedShim(home)
                seedGrok(home)
                runBlocking {
                    SetupCommand(
                        prompts = SetupPrompts(
                            frame = WizardFrame(out = chrome, ask = ConfirmPrompt { _, d -> d }),
                            pickHeads = HeadPicker { options, _ ->
                                offered += options.map { it.value }
                                MultiSelectOutcome.Chosen(emptyList())
                            },
                        ),
                        loginHead = NO_REAL_LOGIN,
                        detect = { emptyFacts() },
                        effects = SetupEffects(
                            EnvReader(System::getenv),
                            add = ProfileAdd { error("empty tick must not add") },
                        ),
                    ).setup()
                }
            }
            assertFalse("grok" in offered, offered.toString())
            assertTrue("already installed: grok" in chrome.toString(), chrome.toString())
        }

        @Test
        fun `console preticks profiles whose credential is already on disk`(@TempDir home: Path) {
            var initial = emptySet<String>()
            val prompts = SetupPrompts(
                hasConsole = { true },
                pickHeads = HeadPicker { _, selected ->
                    initial = selected
                    MultiSelectOutcome.Chosen(emptyList())
                },
            )
            withHome(home) {
                seedShim(home)
                runBlocking {
                    SetupCommand(
                        prompts = prompts,
                        loginHead = NO_REAL_LOGIN,
                        detect = { emptyFacts().copy(spliceOwned = setOf("chatgpt-oauth", "muse-oauth")) },
                        env = EnvReader { null },
                        // A console run reaches the local-model offer; left at its default it would
                        // spawn the real nvidia-smi. No card here, so it is never asked.
                        localModel = SetupLocalModel(
                            prompts,
                            EnvReader { null },
                            DaemonRestart { true },
                            host = RigHost(gpu = GpuProbe { emptyList() }),
                        ),
                        effects = SetupEffects(
                            EnvReader(System::getenv),
                            add = ProfileAdd { error("empty tick must not add") },
                        ),
                    ).setup()
                }
            }
            assertTrue("codex" in initial, initial.toString())
            assertTrue("muse" in initial, initial.toString())
            assertFalse("grok" in initial, initial.toString())
        }

        @Test
        fun `headless pretick is empty even when credentials exist`(@TempDir home: Path) {
            var initial = setOf("sentinel")
            withHome(home) {
                seedShim(home)
                runBlocking {
                    SetupCommand(
                        prompts = SetupPrompts(
                            hasConsole = { false },
                            pickHeads = HeadPicker { _, selected ->
                                initial = selected
                                MultiSelectOutcome.Chosen(emptyList())
                            },
                        ),
                        loginHead = NO_REAL_LOGIN,
                        detect = { emptyFacts().copy(spliceOwned = setOf("chatgpt-oauth")) },
                        effects = SetupEffects(
                            EnvReader(System::getenv),
                            add = ProfileAdd { error("empty tick must not add") },
                        ),
                    ).setup()
                }
            }
            assertEquals(emptySet<String>(), initial)
        }

        @Test
        fun `ticked api-key profiles go through the AddCommand seam`(@TempDir home: Path) {
            val added = mutableListOf<String>()
            val wanted = AddProfiles().catalog().map { it.name }.filter { it == "deepseek" || it == "openrouter" }
            withHome(home) {
                seedShim(home)
                runBlocking {
                    SetupCommand(
                        prompts = SetupPrompts(
                            pickHeads = HeadPicker { _, _ -> MultiSelectOutcome.Chosen(wanted) },
                        ),
                        loginHead = NO_REAL_LOGIN,
                        detect = { emptyFacts() },
                        effects = SetupEffects(
                            EnvReader(System::getenv),
                            add = ProfileAdd { name ->
                                added += name
                                true
                            },
                            restart = DaemonRestart { true },
                        ),
                    ).setup()
                }
            }
            assertEquals(wanted, added)
            assertTrue("deepseek" in added, added.toString())
        }
    }

    private fun emptyFacts() = SetupFacts(
        spliceOwned = emptySet(),
        vendorCli = emptySet(),
        openRouterKey = false,
        daemonUp = false,
    )

    private fun seedShim(home: Path) {
        val share = home.resolve(".local").resolve("share").resolve("splice")
        Files.createDirectories(share)
        Files.writeString(share.resolve("splice-launch"), "#!/usr/bin/env bash\n")
    }

    private fun seedGrok(home: Path) {
        val cfg = home.resolve(".config").resolve("splice")
        Files.createDirectories(cfg)
        Files.writeString(
            cfg.resolve("splice.toml"),
            """
            [daemon]
            control_port = 3096
            [providers.grok]
            dialect = "openai-responses"
            base_url = "https://x"
            auth = { kind = "grok-oauth" }
            [heads.grok]
            provider = "grok"
            port = 3098
            discovery_prefix = "claude-grok--"
            pinned_model = "grok-4.6"
            [heads.grok.claude]
            command = "claude-grok"
            """.trimIndent() + "\n",
        )
    }

    private fun seedOauthTopology(home: Path) {
        val cfg = home.resolve(".config").resolve("splice")
        Files.createDirectories(cfg)
        Files.writeString(
            cfg.resolve("splice.toml"),
            """
            [daemon]
            control_port = 3096
            [providers.codex]
            dialect = "openai-responses"
            base_url = "https://x"
            auth = { kind = "chatgpt-oauth" }
            [heads.claudex]
            provider = "codex"
            port = 3099
            discovery_prefix = "claude-codex--"
            pinned_model = "gpt-5.6-sol"
            [heads.claudex.claude]
            command = "claudex"
            """.trimIndent() + "\n",
        )
    }

    private fun <T> withHome(home: Path, block: () -> T): T {
        return UserHome.within(home) {
            block()
        }
    }

    private fun captureStdout(block: () -> Unit): String {
        val buf = ByteArrayOutputStream()
        val prev = System.out
        System.setOut(PrintStream(buf, true))
        return try {
            block()
            buf.toString()
        } finally {
            System.setOut(prev)
        }
    }
}

/** The wizard's sign-in step. Left at its default, it runs a REAL OAuth login: on 2026-09-16 that
 *  opened accounts.x.ai in the operator's browser on every `:app:test` and then blocked in
 *  awaitCode for a callback that could never arrive, which read for a day as the daemon demanding a
 *  sign-in. Every construction here passes this instead, and LoginIo's wall now fails any test that
 *  forgets. */
private val NO_REAL_LOGIN = HeadSignIn { true }

/** An add that refuses to run: for a setup where nothing is ticked, so nothing real is added, retired or checked. */
private val NOTHING_TICKED = ProfileAdd { error("nothing is ticked") }

/** An env var no machine sets, so the close's readiness check cannot find a key for it. */
private const val CLOSE_KEY = "TEST_SETUP_CLOSE_KEY"

private val HEADLESS_ORACLE = """
[daemon]
control_port = 3096
# Reasoning display (edit + restart; env/PATCH still override):
show_reasoning = "text"
summary = "detailed"
replay_reasoning = true
mcp_share = true

[defaults]
# How far back splice keeps this install's history: the hourly spend totals and the request
# records, as one window. Days, or "forever". Change it here, or on Settings > Your data in the
# console, where shortening it says how many turns it would delete and deletes nothing until you
# say yes. An install made before this line existed keeps the 90 days it already had.
historyRetentionDays = "35"

# No provider or head is selected on first run. Connect a plan with `splice setup`, or add a
# specific profile with `splice add <profile>`. The ChatGPT, Grok, Kimi and Muse sign-in examples
# are in splice.example.toml.
""".trimIndent() + "\n"
