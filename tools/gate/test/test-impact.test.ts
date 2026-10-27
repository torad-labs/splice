// The pre-push runs only the unit test classes a push can affect (test-impact.ts). Simon asked for it on Oct 26 2026, with Marcos's
// approval: map the changed sources to their tests, include the tests of anything that depends on a changed module, fall back to
// the full suite when a build file, gradle/, a version catalog or a shared test fixture changes, and print which mode ran and why.
// splice-lead settled the reach the same day: a test in any module that reaches a changed file through references is selected, and
// whatever the reference analysis cannot see selects every test of the module that owns it, plus the tests in other modules that
// reach that module. This pre-push is the only check while CI is locked, and a wrongly skipped test reaches main, so where the
// selection is unsure it selects more.
import { describe, expect, test } from "bun:test";
import { scanSource } from "../src/lib/kotlin-source.ts";
import {
  confineTo,
  fullSuiteReason,
  impactClause,
  impactLines,
  testArgs,
  testImpact,
  type ImpactInput,
  type ImpactModule,
  type TestImpact,
} from "../src/lib/test-impact.ts";

// ── A small repository: :core holds money and a clock, :app checks out with a wallet, :features-turns keeps turns ──

const MODULES: readonly ImpactModule[] = [
  { path: ":core", dir: "core" },
  { path: ":app", dir: "app" },
  { path: ":features-turns", dir: "features/turns" },
];

const main = (dir: string, pkg: string, file: string): string => `${dir}/src/main/kotlin/${pkg.replaceAll(".", "/")}/${file}`;
const unit = (dir: string, pkg: string, file: string): string => `${dir}/src/test/kotlin/${pkg.replaceAll(".", "/")}/${file}`;
const kt = (pkg: string, ...lines: string[]): string => [`package ${pkg}`, "", ...lines, ""].join("\n");
/** A JUnit test class with one test whose body is [body]. */
const junit = (pkg: string, name: string, body: string, imports: readonly string[] = [], annotations: readonly string[] = []): string =>
  kt(
    pkg,
    "import org.junit.jupiter.api.Tag",
    "import org.junit.jupiter.api.Test",
    ...imports,
    "",
    ...annotations,
    `class ${name} {`,
    "    @Test",
    "    fun works() {",
    `        ${body}`,
    "    }",
    "}",
  );

const MONEY = main("core", "splice.core", "Money.kt");
const CLOCK = main("core", "splice.core", "Clock.kt");
const WALLET = main("core", "splice.core", "Wallet.kt");
const MONEY_TEST = unit("core", "splice.core", "MoneyTest.kt");
const CLOCK_TEST = unit("core", "splice.core", "ClockTest.kt");
const WALLET_TEST = unit("core", "splice.core", "WalletTest.kt");
const CHECKOUT = main("app", "splice.app", "Checkout.kt");
const BANNER = main("app", "splice.app", "Banner.kt");
const CHECKOUT_TEST = unit("app", "splice.app", "CheckoutTest.kt");
const BANNER_TEST = unit("app", "splice.app", "BannerTest.kt");
const DEFAULTS_TEST = unit("app", "splice.app", "DefaultsTest.kt");
const TURN = main("features/turns", "splice.turns", "Turn.kt");
const TURN_TEST = unit("features/turns", "splice.turns", "TurnTest.kt");
const TURN_CLOCK_TEST = unit("features/turns", "splice.turns", "TurnClockTest.kt");

const BASE: Readonly<Record<string, string>> = {
  "settings.gradle.kts": 'rootProject.name = "splice"\n',
  "build.gradle.kts": 'plugins { base }\n',
  "core/build.gradle.kts": 'plugins { id("splice.kotlin-common") }\n',
  "app/build.gradle.kts": 'plugins { id("splice.kotlin-common") }\n',
  "features/turns/build.gradle.kts": 'plugins { id("splice.kotlin-common") }\n',
  [MONEY]: kt("splice.core", "class Money(val cents: Long)"),
  [CLOCK]: kt("splice.core", "class Clock {", "    fun now(): Long = 0", "}"),
  [WALLET]: kt("splice.core", "class Wallet(val balance: Money) {", "    companion object {", "        fun empty() = Wallet(Money(0))", "    }", "}"),
  [MONEY_TEST]: junit("splice.core", "MoneyTest", "check(Money(1).cents == 1L)"),
  [CLOCK_TEST]: junit("splice.core", "ClockTest", "check(Clock().now() == 0L)"),
  [WALLET_TEST]: junit("splice.core", "WalletTest", "check(Wallet.empty().balance.cents == 0L)"),
  "core/src/main/resources/defaults.toml": "currency = 'USD'\n",
  [CHECKOUT]: kt("splice.app", "import splice.core.Wallet", "", "class Checkout(val wallet: Wallet = Wallet.empty())"),
  [BANNER]: kt("splice.app", "class Banner {", '    val text = "hello"', "}"),
  [CHECKOUT_TEST]: junit("splice.app", "CheckoutTest", "check(Checkout().wallet.balance.cents == 0L)"),
  [BANNER_TEST]: junit("splice.app", "BannerTest", "check(Banner().text.isNotEmpty())"),
  [DEFAULTS_TEST]: junit("splice.app", "DefaultsTest", 'check(javaClass.getResource("/defaults.toml") != null)'),
  "app/src/test/resources/goldens/checkout.json": "{}\n",
  [TURN]: kt("splice.turns", "class Turn"),
  [TURN_TEST]: junit("splice.turns", "TurnTest", "check(Turn().hashCode() != 0)"),
  [TURN_CLOCK_TEST]: junit("splice.turns", "TurnClockTest", "check(Clock().now() >= 0L)", ["import splice.core.Clock"]),
};

/** A push of [edits] (a path's new text, or null when the push deletes it) onto [base]. */
function pushOf(edits: Readonly<Record<string, string | null>>, base: Readonly<Record<string, string>> = BASE, modules = MODULES): ImpactInput {
  const tip: Record<string, string> = { ...base };
  for (const [path, text] of Object.entries(edits)) {
    if (text === null) delete tip[path];
    else tip[path] = text;
  }
  return {
    modules,
    changed: Object.keys(edits).sort(),
    tree: {
      paths: Object.keys(tip).sort(),
      read: (path) => {
        const text = tip[path];
        if (text === undefined) throw new Error(`${path} is not in the pushed commit`);
        return text;
      },
      before: (path) => base[path],
    },
  };
}

/** What each module's unit task runs: the selected classes, "all" or "none". */
function shape(impact: TestImpact): Record<string, string | string[]> {
  if (impact.mode === "full") throw new Error(`the full suite ran: ${impact.why}`);
  return Object.fromEntries([...impact.modules].map(([module, plan]) => [module, plan.kind === "classes" ? [...plan.classes] : plan.kind]));
}

/** The class the filter of [module] keeps so that it matches a test, when the selected ones are not sure to run one. */
function keeperOf(impact: TestImpact, module: string): string | undefined {
  if (impact.mode === "full") throw new Error(`the full suite ran: ${impact.why}`);
  const plan = impact.modules.get(module);
  if (plan?.kind !== "classes") throw new Error(`${module} runs no filtered classes: ${JSON.stringify(plan)}`);
  return plan.keeper;
}

/** Why [module] runs whole. */
function whyWhole(impact: TestImpact, module: string): string {
  if (impact.mode === "full") throw new Error(`the full suite ran: ${impact.why}`);
  const plan = impact.modules.get(module);
  if (plan?.kind !== "all") throw new Error(`${module} does not run whole: ${JSON.stringify(plan)}`);
  return plan.why;
}

describe("the tests a change reaches", () => {
  test("a changed class selects the tests that reach it, in its own module and in others, and no other test", () => {
    const impact = testImpact(pushOf({ [MONEY]: kt("splice.core", 'class Money(val cents: Long, val currency: String = "USD")') }));
    expect(shape(impact)).toEqual({
      ":core": ["splice.core.MoneyTest", "splice.core.WalletTest"],
      ":app": ["splice.app.CheckoutTest"],
      ":features-turns": "none",
    });
  });

  test("a test that names a changed class from another module's tests is selected, though its module's code never names it", () => {
    const impact = testImpact(pushOf({ [CLOCK]: kt("splice.core", "class Clock {", "    fun now(): Long = 1", "}") }));
    expect(shape(impact)).toEqual({ ":core": ["splice.core.ClockTest"], ":app": "none", ":features-turns": ["splice.turns.TurnClockTest"] });
  });

  test("a changed test file selects its own classes", () => {
    const impact = testImpact(pushOf({ [BANNER_TEST]: junit("splice.app", "BannerTest", 'check(Banner().text == "hello")') }));
    expect(shape(impact)).toEqual({ ":core": "none", ":app": ["splice.app.BannerTest"], ":features-turns": "none" });
  });

  test("a class named in a string literal is a reference, and one named only in a comment is not", () => {
    const registry = main("core", "splice.core", "Registry.kt");
    const notes = main("core", "splice.core", "Notes.kt");
    const base = {
      ...BASE,
      [registry]: kt("splice.core", "object Registry {", '    val names = listOf("splice.core.Clock")', "}"),
      [notes]: kt(
        "splice.core",
        "/** Unlike [Clock], this keeps no time. */",
        "object Notes {",
        "    // Clock is not read here",
        "    /* nor /* in a nested comment */ Clock */",
        '    val text = "notes"',
        "}",
      ),
      [unit("core", "splice.core", "RegistryTest.kt")]: junit("splice.core", "RegistryTest", "check(Registry.names.size == 1)"),
      [unit("core", "splice.core", "NotesTest.kt")]: junit("splice.core", "NotesTest", "check(Notes.text.isNotEmpty())"),
    };
    const impact = testImpact(pushOf({ [CLOCK]: kt("splice.core", "class Clock {", "    fun now(): Long = 2", "}") }, base));
    expect(shape(impact)).toEqual({
      ":core": ["splice.core.ClockTest", "splice.core.RegistryTest"],
      ":app": "none",
      ":features-turns": ["splice.turns.TurnClockTest"],
    });
  });

  test("a new subclass of a sealed type selects the tests of everything that uses the type", () => {
    const base = {
      ...BASE,
      [main("core", "splice.core", "Shape.kt")]: kt("splice.core", "sealed interface Shape"),
      [main("core", "splice.core", "Circle.kt")]: kt("splice.core", "class Circle(val r: Int) : Shape"),
      [main("core", "splice.core", "Area.kt")]: kt("splice.core", "object Area {", "    fun of(s: Shape): Int = when (s) {", "        is Circle -> 3", "    }", "}"),
      [unit("core", "splice.core", "AreaTest.kt")]: junit("splice.core", "AreaTest", "check(Area.of(Circle(1)) == 3)"),
      [main("app", "splice.app", "ShapeView.kt")]: kt("splice.app", "import splice.core.Shape", "", "class ShapeView(val shape: Shape)"),
      [unit("app", "splice.app", "ShapeViewTest.kt")]: junit("splice.app", "ShapeViewTest", "check(ShapeView::class.simpleName != null)"),
    };
    const square = testImpact(pushOf({ [main("core", "splice.core", "Square.kt")]: kt("splice.core", "class Square(val side: Int) : Shape") }, base));
    expect(shape(square)).toEqual({ ":core": ["splice.core.AreaTest"], ":app": ["splice.app.ShapeViewTest"], ":features-turns": "none" });

    // The rule is the compiler's: a sealed type's subclasses live in its own package and module, so a file elsewhere that names it is a user.
    const view = testImpact(pushOf({ [main("app", "splice.app", "ShapeView.kt")]: kt("splice.app", "import splice.core.Shape", "", "class ShapeView(val shape: Shape?)") }, base));
    expect(shape(view)).toEqual({ ":core": "none", ":app": ["splice.app.ShapeViewTest"], ":features-turns": "none" });
  });

  test("a top-level operator selects the files of its package and the files that import its package whole, which use it unnamed", () => {
    const math = main("core", "splice.core.math", "MoneyMath.kt");
    const base = {
      ...BASE,
      [math]: kt("splice.core.math", "import splice.core.Money", "", "operator fun Money.plus(other: Money): Money = Money(cents + other.cents)"),
      [unit("core", "splice.core.math", "SumTest.kt")]: junit("splice.core.math", "SumTest", "check((Money(1) + Money(2)).cents == 3L)", ["import splice.core.Money"]),
      [unit("app", "splice.app", "TotalsTest.kt")]: junit("splice.app", "TotalsTest", "check((Wallet.empty().balance + Wallet.empty().balance).cents == 0L)", [
        "import splice.core.Wallet",
        "import splice.core.math.*",
      ]),
      [unit("app", "splice.app", "NamedPlusTest.kt")]: junit("splice.app", "NamedPlusTest", "check((Wallet.empty().balance + Wallet.empty().balance).cents == 0L)", [
        "import splice.core.Wallet",
        "import splice.core.math.plus",
      ]),
    };
    const impact = testImpact(pushOf({ [math]: `${base[math]}\n// faster\n` }, base));
    expect(shape(impact)).toEqual({
      ":core": ["splice.core.math.SumTest"],
      ":app": ["splice.app.NamedPlusTest", "splice.app.TotalsTest"],
      ":features-turns": "none",
    });
  });

  test("a deleted file selects the tests that still reach it", () => {
    const impact = testImpact(pushOf({ [CLOCK]: null }));
    expect(shape(impact)).toEqual({ ":core": ["splice.core.ClockTest"], ":app": "none", ":features-turns": ["splice.turns.TurnClockTest"] });
  });

  test("a renamed class selects the tests that name it by its old name or its new one", () => {
    const impact = testImpact(
      pushOf({
        [MONEY]: kt("splice.core", "class Cash(val cents: Long)"),
        [unit("core", "splice.core", "CashTest.kt")]: junit("splice.core", "CashTest", "check(Cash(1).cents == 1L)"),
      }),
    );
    expect(shape(impact)).toEqual({
      ":core": ["splice.core.CashTest", "splice.core.MoneyTest", "splice.core.WalletTest"],
      ":app": ["splice.app.CheckoutTest"],
      ":features-turns": "none",
    });
  });

  test("a Java source is read as Java: its own comments, its own strings", () => {
    const native = "core/src/main/java/splice/core/Native.java";
    const base = {
      ...BASE,
      [native]: "package splice.core;\n\n/* a Java comment does not nest: /* */\npublic final class Native {\n    static final String S = \"/* not a comment\";\n}\n",
      [unit("core", "splice.core", "NativeTest.kt")]: junit("splice.core", "NativeTest", "check(Native.S.isNotEmpty())"),
    };
    const impact = testImpact(pushOf({ [native]: base[native].replace("not a comment", "still not a comment") }, base));
    expect(shape(impact)).toEqual({ ":core": ["splice.core.NativeTest"], ":app": "none", ":features-turns": "none" });
  });

  test("a module whose sources have no test folder is left out, and its tests run as the build says", () => {
    const hub: ImpactModule = { path: ":integrations-mcp-hub", dir: "integrations/mcp-hub" };
    const path = "integrations/mcp-hub/src/linuxX64Main/kotlin/splice/hub/Hub.kt";
    const base = { ...BASE, [path]: kt("splice.hub", "class Hub") };
    const impact = testImpact(pushOf({ [path]: kt("splice.hub", "class Hub(val port: Int)") }, base, [...MODULES, hub]));
    expect(shape(impact)).toEqual({ ":core": "none", ":app": "none", ":features-turns": "none" });
  });

  test("a module whose test task another task depends on by name is never narrowed", () => {
    const base = { ...BASE, "build-logic/src/main/kotlin/splice.gate-ladder.gradle.kts": 'tasks.register("lawSuites") {\n    dependsOn(":features-turns:test")\n}\n' };
    const impact = testImpact(pushOf({ [MONEY]: kt("splice.core", "class Money(val cents: Int)") }, base));
    expect(shape(impact)).toEqual({ ":core": ["splice.core.MoneyTest", "splice.core.WalletTest"], ":app": ["splice.app.CheckoutTest"] });
  });
});

describe("what the reference analysis cannot see runs its module whole, and the tests that reach that module", () => {
  test("a changed resource runs its module whole, the tests in other modules that reach it, and the tests that name it", () => {
    const impact = testImpact(pushOf({ "core/src/main/resources/defaults.toml": "currency = 'EUR'\n" }));
    expect(shape(impact)).toEqual({
      ":core": "all",
      ":app": ["splice.app.CheckoutTest", "splice.app.DefaultsTest"],
      ":features-turns": ["splice.turns.TurnClockTest"],
    });
    expect(whyWhole(impact, ":core")).toContain("core/src/main/resources/defaults.toml");
  });

  test("a changed golden runs its module whole", () => {
    const impact = testImpact(pushOf({ "app/src/test/resources/goldens/checkout.json": '{"total": 0}\n' }));
    expect(shape(impact)).toEqual({ ":core": "none", ":app": "all", ":features-turns": "none" });
    expect(whyWhole(impact, ":app")).toContain("app/src/test/resources/goldens/checkout.json");
  });

  test("a changed source the scan cannot read runs its module whole", () => {
    const impact = testImpact(pushOf({ [TURN]: kt("splice.turns", "class Turn {", '    val s = "never closed', "}") }));
    expect(shape(impact)).toEqual({ ":core": "none", ":app": "none", ":features-turns": "all" });
    expect(whyWhole(impact, ":features-turns")).toContain(TURN);
  });

  test("an unchanged source the scan cannot read still runs its module whole, since its references are unknown", () => {
    const broken = main("features/turns", "splice.turns", "Broken.kt");
    const base = { ...BASE, [broken]: kt("splice.turns", "/* never closed", "class Hidden") };
    const impact = testImpact(pushOf({ [BANNER]: kt("splice.app", "class Banner {", '    val text = "hi"', "}") }, base));
    expect(shape(impact)).toEqual({ ":core": "none", ":app": ["splice.app.BannerTest"], ":features-turns": "all" });
    expect(whyWhole(impact, ":features-turns")).toContain(broken);
  });

  test("a changed class the root build names runs the full suite", () => {
    const base = { ...BASE, "build.gradle.kts": 'plugins { base }\n\nval cleanup = "splice.core.Clock"\n' };
    const impact = testImpact(pushOf({ [CLOCK]: kt("splice.core", "class Clock {", "    fun now(): Long = 3", "}") }, base));
    expect(impact.mode).toBe("full");
  });

  test("a changed class a resource names, as a service file does, runs its module whole", () => {
    const base = { ...BASE, "core/src/main/resources/META-INF/services/splice.core.Plugin": "splice.core.Clock\n" };
    const impact = testImpact(pushOf({ [CLOCK]: kt("splice.core", "class Clock {", "    fun now(): Long = 4", "}") }, base));
    expect(shape(impact)).toEqual({ ":core": "all", ":app": ["splice.app.CheckoutTest"], ":features-turns": ["splice.turns.TurnClockTest"] });
  });

  test("a changed class named only in shipped Markdown prose keeps the narrowing: prose loads no class, and the laws that read docs run on every push", () => {
    const doc = "app/src/main/resources/manager-home/reference/operations.md";
    const base = { ...BASE, [doc]: "# operations\n\nThe mount is `splice.core.Clock`, which answers the hour.\n" };
    const impact = testImpact(pushOf({ [CLOCK]: kt("splice.core", "class Clock {", "    fun now(): Long = 7", "}") }, base));
    // Nothing in :app reaches the clock: its checkout holds a wallet, and its own tests read a resource.
    expect(shape(impact)).toEqual({
      ":core": ["splice.core.ClockTest"],
      ":app": "none",
      ":features-turns": ["splice.turns.TurnClockTest"],
    });
    // The same name in a resource a test or the runtime reads still runs that resource's module whole.
    const asJson = { ...BASE, "app/src/main/resources/mounts.json": '{"mount": "splice.core.Clock"}\n' };
    expect(shape(testImpact(pushOf({ [CLOCK]: kt("splice.core", "class Clock {", "    fun now(): Long = 8", "}") }, asJson)))).toEqual({
      ":core": ["splice.core.ClockTest"],
      ":app": "all",
      ":features-turns": ["splice.turns.TurnClockTest"],
    });
  });

  test("a resource that names a changed class runs the whole module that OWNS the resource, and leaves the class's own module narrowed", () => {
    const golden = "app/src/test/resources/goldens/clock.json";
    const base = { ...BASE, [golden]: '{"kind": "splice.core.Clock", "now": 0}\n' };
    const impact = testImpact(pushOf({ [CLOCK]: kt("splice.core", "class Clock {", "    fun now(): Long = 9", "}") }, base));
    // :app holds the golden, so its tests may read the name from it; nothing in :core reads :app's test resources.
    expect(shape(impact)).toEqual({ ":core": ["splice.core.ClockTest"], ":app": "all", ":features-turns": ["splice.turns.TurnClockTest"] });
    expect(whyWhole(impact, ":app")).toBe(`splice.core.Clock changed and ${golden} names it`);
  });

  test("a build script that names a changed class of another module runs that script's module whole, not the changed module", () => {
    const base = { ...BASE, "app/build.gradle.kts": 'plugins { id("splice.kotlin-common") }\n\nval mount = "splice.core.Clock"\n' };
    const impact = testImpact(pushOf({ [CLOCK]: kt("splice.core", "class Clock {", "    fun now(): Long = 10", "}") }, base));
    expect(shape(impact)).toEqual({ ":core": ["splice.core.ClockTest"], ":app": "all", ":features-turns": ["splice.turns.TurnClockTest"] });
    expect(whyWhole(impact, ":app")).toBe("splice.core.Clock changed and app/build.gradle.kts names it");
  });

  test("a changed Markdown doc runs the tests that name the file, not its module whole", () => {
    const doc = "app/src/main/resources/manager-home/reference/operations.md";
    const reader = unit("app", "splice.app", "OperationsDocTest.kt");
    const base = {
      ...BASE,
      [doc]: "# operations\n\nThe mount answers the hour.\n",
      [reader]: junit("splice.app", "OperationsDocTest", 'check(javaClass.getResource("/manager-home/reference/operations.md") != null)'),
    };
    const impact = testImpact(pushOf({ [doc]: "# operations\n\nThe mount answers the hour, in the machine's zone.\n" }, base));
    expect(shape(impact)).toEqual({ ":core": "none", ":app": ["splice.app.OperationsDocTest"], ":features-turns": "none" });
  });

  test("code that loads classes by a computed name runs its module whole on any source change; a literal name is a plain reference", () => {
    const plugins = main("app", "splice.app", "Plugins.kt");
    const loader = main("app", "splice.app", "Loader.kt");
    const base = {
      ...BASE,
      [plugins]: kt("splice.app", "object Plugins {", "    fun all(): List<Runnable> = java.util.ServiceLoader.load(Runnable::class.java).toList()", "}"),
      [loader]: kt("splice.app", "object Loader {", '    fun clock(): Any = Class.forName("splice.core.Clock")', "}"),
      [unit("app", "splice.app", "LoaderTest.kt")]: junit("splice.app", "LoaderTest", "check(Loader.clock() != null)"),
    };
    const turn = testImpact(pushOf({ [TURN]: kt("splice.turns", "class Turn(val n: Int = 0)") }, base));
    expect(shape(turn)).toEqual({ ":core": "none", ":app": "all", ":features-turns": ["splice.turns.TurnTest"] });
    expect(whyWhole(turn, ":app")).toContain(plugins);

    const withoutDiscovery = { ...base };
    delete withoutDiscovery[plugins];
    const clock = testImpact(pushOf({ [CLOCK]: kt("splice.core", "class Clock {", "    fun now(): Long = 5", "}") }, withoutDiscovery));
    expect(shape(clock)).toEqual({ ":core": ["splice.core.ClockTest"], ":app": ["splice.app.LoaderTest"], ":features-turns": ["splice.turns.TurnClockTest"] });
  });

  test("a source the build generates from a module's classes is reached by any change to those classes", () => {
    const base = {
      ...BASE,
      "core/build.gradle.kts":
        'plugins { id("splice.kotlin-common") }\n\nval identity = layout.buildDirectory.dir("generated/sources/identity")\n' +
        'tasks.register<splice.release.Identity>("identity") {\n    source.set(identity.map { it.file("splice/core/Fingerprint.java") })\n}\n' +
        "sourceSets.main { java.srcDir(identity) }\n",
      [main("core", "splice.core", "Stamp.kt")]: kt("splice.core", "object Stamp {", "    fun id(): String = Fingerprint.VALUE", "}"),
      [unit("core", "splice.core", "StampTest.kt")]: junit("splice.core", "StampTest", "check(Stamp.id().isNotEmpty())"),
    };
    const impact = testImpact(pushOf({ [CLOCK]: kt("splice.core", "class Clock {", "    fun now(): Long = 6", "}") }, base));
    expect(shape(impact)).toEqual({ ":core": ["splice.core.ClockTest", "splice.core.StampTest"], ":app": "none", ":features-turns": ["splice.turns.TurnClockTest"] });
  });

  test("a path outside every module selects nothing unless a module's build script names it", () => {
    expect(shape(testImpact(pushOf({ "docs/notes.md": "# notes\n", "tools/gate/src/x.ts": "export {};\n", "README.md": "hi\n" })))).toEqual({
      ":core": "none",
      ":app": "none",
      ":features-turns": "none",
    });
    const base = { ...BASE, "app/build.gradle.kts": 'plugins { id("splice.kotlin-common") }\n\nval conformance = rootProject.file("fixtures/conformance")\n', "fixtures/conformance/basic.json": "{}\n" };
    const impact = testImpact(pushOf({ "fixtures/conformance/basic.json": '{"model": "x"}\n' }, base));
    expect(shape(impact)).toEqual({ ":core": "none", ":app": "all", ":features-turns": "none" });
    expect(whyWhole(impact, ":app")).toContain("fixtures/conformance/basic.json");
  });
});

describe("the classes a filter names must run a test, or the module runs whole", () => {
  test("a law runs in lawTest, so a selection of laws alone leaves the unit task nothing", () => {
    const lane = main("features/turns", "splice.turns", "Lane.kt");
    const base = {
      ...BASE,
      [lane]: kt("splice.turns", "class Lane"),
      [unit("features/turns", "splice.turns", "LaneLawTest.kt")]: junit("splice.turns", "LaneLawTest", "check(Lane() != null)", [], ['@Tag("law")']),
      [unit("core", "splice.core", "MoneyLawTest.kt")]: junit("splice.core", "MoneyLawTest", "check(Money(1).cents > 0)", [], ['@Tag("law")']),
    };
    expect(shape(testImpact(pushOf({ [lane]: kt("splice.turns", "class Lane(val n: Int = 0)") }, base)))).toEqual({ ":core": "none", ":app": "none", ":features-turns": "none" });
    expect(shape(testImpact(pushOf({ [MONEY]: kt("splice.core", "class Money(val cents: Int)") }, base)))).toEqual({
      ":core": ["splice.core.MoneyTest", "splice.core.WalletTest"],
      ":app": ["splice.app.CheckoutTest"],
      ":features-turns": "none",
    });
  });

  // A filter that matches no test fails the task ("No tests found for given includes"), and the scan cannot always tell that a
  // selected class runs one: a test inherited from a parent, or an expression body whose type it cannot resolve. One class of the
  // module that surely runs a test joins the filter, so the narrowing holds and the task has a test to run.
  const inheritance = (): Record<string, string> => {
    const kit = unit("features/turns", "splice.turns", "TurnKit.kt");
    return {
      ...BASE,
      [kit]: kt("splice.turns", "object TurnKit {", "    fun make() = Turn()", "}"),
      [unit("features/turns", "splice.turns", "TurnContract.kt")]: kt(
        "splice.turns",
        "import org.junit.jupiter.api.Test",
        "",
        "abstract class TurnContract {",
        "    @Test",
        "    fun makes() {",
        "        check(TurnKit.make() != null)",
        "    }",
        "}",
      ),
      [unit("features/turns", "splice.turns", "TurnContractTest.kt")]: kt("splice.turns", "class TurnContractTest : TurnContract()"),
    };
  };
  const KIT = unit("features/turns", "splice.turns", "TurnKit.kt");
  const KIT_CHANGE = { [KIT]: kt("splice.turns", "object TurnKit {", "    fun make(): Turn = Turn()", "}") };

  test("a selection with no class sure to run a test keeps one sure class of the module in the filter, and narrows as usual", () => {
    const impact = testImpact(pushOf(KIT_CHANGE, inheritance()));
    expect(shape(impact)).toEqual({
      ":core": "none",
      ":app": "none",
      ":features-turns": ["splice.turns.TurnContract", "splice.turns.TurnContractTest", "splice.turns.TurnKit", "splice.turns.TurnTest"],
    });
    expect(keeperOf(impact, ":features-turns")).toBe("splice.turns.TurnTest");
  });

  test("a module with no test class at all that is sure to run a test runs whole, since any filter of it could match nothing", () => {
    const base = inheritance();
    delete base[TURN_TEST];
    delete base[TURN_CLOCK_TEST];
    const impact = testImpact(pushOf(KIT_CHANGE, base));
    expect(shape(impact)).toEqual({ ":core": "none", ":app": "none", ":features-turns": "all" });
    expect(whyWhole(impact, ":features-turns")).toBe("no test class of the module is sure to run a JUnit test, and a filter that runs none fails");
  });

  test("a changed test class its own module's build script names by its full name runs that module whole: the build uses it its own way", () => {
    const flaky = unit("core", "splice.core", "FlakyTest.kt");
    const base = {
      ...BASE,
      "core/build.gradle.kts": 'plugins { id("splice.kotlin-common") }\n\ntasks.test {\n    filter { excludeTestsMatching("splice.core.FlakyTest") }\n}\n',
      [flaky]: junit("splice.core", "FlakyTest", "check(Money(2).cents == 2L)"),
    };
    const impact = testImpact(pushOf({ [flaky]: junit("splice.core", "FlakyTest", "check(Money(3).cents == 3L)") }, base));
    expect(shape(impact)).toEqual({ ":core": "all", ":app": "none", ":features-turns": "none" });
    expect(whyWhole(impact, ":core")).toBe("splice.core.FlakyTest changed and core/build.gradle.kts names it");
  });

  test("a selected class the build script can exclude by name is not trusted to run, so a class it cannot exclude joins the filter", () => {
    const flakySource = main("core", "splice.core", "Flaky.kt");
    const flaky = unit("core", "splice.core", "FlakyTest.kt");
    const base = {
      ...BASE,
      "core/build.gradle.kts": 'plugins { id("splice.kotlin-common") }\n\ntasks.test {\n    filter { excludeTestsMatching("*FlakyTest") }\n}\n',
      [flakySource]: kt("splice.core", "class Flaky"),
      [flaky]: junit("splice.core", "FlakyTest", "check(Flaky() != null)"),
    };
    // Whether the pattern's class is reached through its source or changed itself, the filter keeps a class the build cannot exclude.
    for (const edits of [{ [flakySource]: kt("splice.core", "class Flaky(val tries: Int = 1)") }, { [flaky]: junit("splice.core", "FlakyTest", "check(Flaky().hashCode() != 0)") }]) {
      const impact = testImpact(pushOf(edits, base));
      expect(shape(impact)).toEqual({ ":core": ["splice.core.ClockTest", "splice.core.FlakyTest"], ":app": "none", ":features-turns": "none" });
      expect(keeperOf(impact, ":core")).toBe("splice.core.ClockTest");
    }
  });
});

const TRIGGERS: readonly string[] = [
  "build.gradle.kts",
  "settings.gradle.kts",
  "core/build.gradle.kts",
  "features/turns/build.gradle",
  "settings.gradle",
  "gradle/libs.versions.toml",
  "gradle/wrapper/gradle-wrapper.properties",
  "gradle/module-law.txt",
  "app/gradle/fork-home/bin/java",
  "catalogs/tools.versions.toml",
  "gradle.properties",
  "core/gradle.properties",
  "gradlew",
  "gradlew.bat",
  "build-logic/src/main/kotlin/splice.kotlin-common.gradle.kts",
  "build-logic/src/main/kotlin/splice/ladder/LadderTable.kt",
  "buildSrc/src/main/kotlin/Versions.kt",
  "quality/compiler-plugin/src/main/kotlin/splice/plugin/Rewrite.kt",
  "package.json",
  "core/src/testFixtures/kotlin/splice/core/FakeClock.kt",
  "core/src/testFixtures/resources/META-INF/services/org.junit.jupiter.api.extension.Extension",
  "app/src/lawFixtures/kotlin/splice/app/LawKit.kt",
];

describe("the full suite runs when the build or a shared fixture changes, and only then", () => {
  test("each trigger runs the full suite and names itself as the reason", () => {
    for (const path of TRIGGERS) {
      expect({ path, reason: fullSuiteReason(path) !== undefined }).toEqual({ path, reason: true });
      const impact = testImpact(pushOf({ [path]: "changed\n", [MONEY]: kt("splice.core", "class Money(val cents: Int)") }));
      expect({ path, mode: impact.mode }).toEqual({ path, mode: "full" });
      if (impact.mode === "full") expect(impact.why).toContain(path);
    }
  });

  test("a source in a package named gradle, a doc about gradle and a resource shaped like a build file are not triggers", () => {
    for (const path of ["core/src/main/kotlin/splice/core/gradle/Probe.kt", "docs/gradle.md", "app/src/test/resources/fixtures/sample.gradle.kts", "tools/gate/package-lock.json"]) {
      expect({ path, reason: fullSuiteReason(path) }).toEqual({ path, reason: undefined });
    }
  });
});

describe("the gradle arguments that carry the selection", () => {
  const ALL = [":core", ":app", ":features-turns"];
  const CHECKED = [":core", ":app", ":features-turns"];
  const SELECTED: TestImpact = {
    mode: "selected",
    changed: 1,
    modules: new Map([
      [":core", { kind: "classes", classes: ["splice.core.MoneyTest", "splice.core.WalletTest"] }],
      [":app", { kind: "all", why: "core/src/main/resources/defaults.toml is a resource" }],
      [":features-turns", { kind: "none" }],
    ]),
  };

  test("selected classes filter their module's test task, which its requested check runs", () => {
    expect(testArgs(confineTo(SELECTED, CHECKED), CHECKED, ALL).tasks.slice(0, 3)).toEqual([
      ":core:test",
      "--tests=splice.core.MoneyTest*",
      "--tests=splice.core.WalletTest*",
    ]);
  });

  test("a whole module adds no task: the check the scope requested already runs its tests", () => {
    expect(testArgs(confineTo(SELECTED, CHECKED), CHECKED, ALL).tasks).not.toContain(":app:test");
  });

  test("a module with nothing selected drops its unit task from its check and still compiles its tests", () => {
    expect(testArgs(confineTo(SELECTED, CHECKED), CHECKED, ALL)).toEqual({
      tasks: [":core:test", "--tests=splice.core.MoneyTest*", "--tests=splice.core.WalletTest*", ":features-turns:testClasses"],
      options: ["--exclude-task=:features-turns:test"],
    });
  });

  test("the selection never adds a module the scope left out: it narrows the checks the push already runs", () => {
    const confined = confineTo(SELECTED, [":core"]);
    const args = testArgs(confined, [":core"], ALL);
    expect(args.tasks.filter((task) => task.startsWith(":app") || task.startsWith(":features-turns"))).toEqual([]);
    expect(args.options).toEqual([]);
    expect(impactLines(confined).join("\n")).not.toContain(":app");
  });

  test("a push that checks no module narrows nothing, and says the gate runs no module's tests", () => {
    const confined = confineTo(SELECTED, []);
    expect(testArgs(confined, [], ALL)).toEqual({ tasks: [], options: [] });
    expect(impactLines(confined)).toEqual(["tests: not narrowed, because no module this push checks has a unit suite of its own"]);
    expect(impactClause(confined)).toBe("tests: not narrowed");
  });

  test("the full suite checks every module the scope left out, and confining leaves it whole", () => {
    const full: TestImpact = { mode: "full", why: "gradle/libs.versions.toml is a version catalog" };
    expect(confineTo(full, [":core"])).toEqual(full);
    expect(testArgs(full, [":core"], ALL)).toEqual({ tasks: [":app:check", ":features-turns:check"], options: [] });
  });
});

describe("the pre-push prints which mode ran and why", () => {
  test("the full suite names its reason", () => {
    const lines = impactLines(testImpact(pushOf({ "gradle/libs.versions.toml": "[versions]\n" })));
    expect(lines[0]).toContain("full suite");
    expect(lines[0]).toContain("gradle/libs.versions.toml");
  });

  test("a narrowed run names what it narrowed to, and why each module runs what it runs", () => {
    const lines = impactLines(testImpact(pushOf({ "core/src/main/resources/defaults.toml": "currency = 'EUR'\n" })));
    expect(lines[0]).toContain("narrowed");
    const text = lines.join("\n");
    expect(text).toContain(":core");
    expect(text).toContain("core/src/main/resources/defaults.toml");
    expect(text).toContain("CheckoutTest");
    expect(text).toContain(":features-turns");
  });

  test("a module whose filter keeps a class the change does not reach says so, and the kept class is not counted as reached", () => {
    const impact: TestImpact = {
      mode: "selected",
      changed: 1,
      modules: new Map([[":core", { kind: "classes", classes: ["splice.core.ClockTest", "splice.core.FlakyTest"], keeper: "splice.core.ClockTest" }]]),
    };
    expect(impactLines(impact)).toEqual([
      "tests: narrowed to what the 1 changed file reaches: 1 class in 1 module, 0 modules whole, 0 modules with no test to run",
      "  :core: FlakyTest, and ClockTest, which the change does not reach, so the filter has a test to run",
    ]);
    expect(impactClause(impact)).toBe("tests: 1 class in 1 module, 0 whole, 0 none");
  });
});

describe("the scan of one source", () => {
  test("declarations: the top-level names other files can name, never a member or a file-private function", () => {
    const scan = scanSource(
      kt(
        "splice.core",
        "import splice.other.*",
        "",
        '@file:JvmName("Moneys")',
        "",
        "data class Money(val cents: Long, val label: String = \"x)\")",
        "sealed interface Shape {",
        "    sealed class Inner : Shape",
        "    val area: Int",
        "}",
        "fun <T : Comparable<T>> List<T>.sortedMoney(by: (T) -> Int = { 0 }): Map<String, List<T>> = emptyMap()",
        "fun ((Int) -> Unit).twice() = this(2)",
        "val String.cents: Long get() = 0",
        "val <T> List<T>.second: T?",
        "    get() = getOrNull(1)",
        "operator fun Money.plus(other: Money) = Money(cents + other.cents)",
        "private fun helper() = 1",
        "private val cache = mutableMapOf<String, Int>()",
        "typealias Ledger = Map<String, Money>",
        "fun interface Pricer { fun price(m: Money): Long }",
        "val anonymous = object : Runnable { override fun run() {} }",
        "val kind = Money::class",
        "fun `with spaces`() = Unit",
      ),
      "core/src/main/kotlin/splice/core/Money.kt",
    );
    expect(scan.parsed).toBe(true);
    expect(scan.packageName).toBe("splice.core");
    expect([...scan.declared].sort()).toEqual(
      ["Ledger", "Money", "MoneyKt", "Moneys", "Pricer", "Shape", "anonymous", "cents", "kind", "plus", "second", "sortedMoney", "twice", "with spaces"].sort(),
    );
    expect([...scan.sealed].sort()).toEqual(["Inner", "Shape"]);
    expect(scan.operators).toBe(true);
    expect(scan.wildcards).toEqual(["splice.other"]);
  });

  test("tokens: names in code and in strings, through templates and raw strings, and none from comments", () => {
    const scan = scanSource(
      kt(
        "splice.core",
        "fun f() {",
        '    val a = "/* not a comment ${Alpha}" + "$Beta" + """raw ${Gamma} "quoted" """ + \'"\' + "\\"" + `Delta`',
        "    /* Hidden1 /* nested Hidden2 */ Hidden3 */",
        "    // Hidden4",
        "    val b = Epsilon",
        "}",
      ),
      "core/src/main/kotlin/splice/core/F.kt",
    );
    expect(scan.parsed).toBe(true);
    for (const name of ["Alpha", "Beta", "Gamma", "Delta", "Epsilon", "not", "comment", "raw", "quoted"]) expect({ name, seen: scan.tokens.has(name) }).toEqual({ name, seen: true });
    for (const name of ["Hidden1", "Hidden2", "Hidden3", "Hidden4"]) expect({ name, seen: scan.tokens.has(name) }).toEqual({ name, seen: false });
  });

  test("test classes: a law, a runnable class, and classes JUnit may run nothing in", () => {
    const scan = scanSource(
      kt(
        "splice.core",
        "import org.junit.jupiter.api.*",
        "",
        '@Tag("law")',
        "class LawTest {",
        "    @Test fun law() {}",
        "}",
        "",
        "@TestInstance(TestInstance.Lifecycle.PER_CLASS)",
        "class PlainTest {",
        "    @Test",
        "    fun plain() {",
        "        check(true)",
        "    }",
        "}",
        "abstract class BaseTest {",
        "    @Test fun inherited() {}",
        "}",
        "@Disabled class DisabledTest {",
        "    @Test fun off() {}",
        "}",
        "class ExpressionTest {",
        "    @Test fun returnsSomething() = listOf(1)",
        "}",
        "class ParamTest(val x: Int) {",
        "    @Test fun p() {}",
        "}",
        "class PrivateTest {",
        "    @Test private fun hidden() {}",
        "}",
        "class UnitTypedTest {",
        "    @ParameterizedTest @ValueSource(ints = [1]) fun typed(x: Int): Unit = check(x == 1)",
        "}",
      ),
      "core/src/test/kotlin/splice/core/Tests.kt",
    );
    const classes = Object.fromEntries(scan.classes.map((c) => [c.name, { law: c.law, runnable: c.runnable }]));
    expect(classes).toEqual({
      LawTest: { law: true, runnable: false },
      PlainTest: { law: false, runnable: true },
      BaseTest: { law: false, runnable: false },
      DisabledTest: { law: false, runnable: false },
      ExpressionTest: { law: false, runnable: false },
      ParamTest: { law: false, runnable: false },
      PrivateTest: { law: false, runnable: false },
      UnitTypedTest: { law: false, runnable: true },
    });
  });

  test("a test whose body is kotlinx's runTest returns nothing, so JUnit runs it; a runTest from elsewhere may return a value", () => {
    const sure = (importLine: string, body: string): boolean | undefined =>
      scanSource(
        kt(
          "splice.core",
          "import org.junit.jupiter.api.Test",
          importLine,
          "",
          "class CoroutineTest {",
          "    @Test",
          "    fun `waits for the clock`(@TempDir tmp: Path) =",
          `        ${body}`,
          "}",
        ),
        "core/src/test/kotlin/splice/core/CoroutineTest.kt",
      ).classes[0]?.runnable;
    for (const body of ["runTest { check(true) }", "runTest(timeout = 5.seconds) { check(true) }"]) {
      expect({ body, sure: sure("import kotlinx.coroutines.test.runTest", body) }).toEqual({ body, sure: true });
      expect({ body, sure: sure("import kotlinx.coroutines.test.*", body) }).toEqual({ body, sure: true });
      expect({ body, sure: sure("import splice.core.testing.runTest", body) }).toEqual({ body, sure: false });
    }
  });

  test("an unterminated comment, string or brace is a source the scan cannot read", () => {
    for (const text of ['val s = "open\nval t = 1\n', "/* open\nclass A\n", "class A {\n", "class A }\n", 'val s = """open\n', "val s = \"${open\"\n"]) {
      expect({ text, parsed: scanSource(kt("splice.core", text), "core/src/main/kotlin/splice/core/A.kt").parsed }).toEqual({ text, parsed: false });
    }
  });

  test("loading a class by a computed name, a service loader or a classpath scan is discovery; a literal class name is not", () => {
    const loads = (line: string): boolean => scanSource(kt("splice.core", "fun f(name: String) {", `    ${line}`, "}"), "core/src/main/kotlin/splice/core/L.kt").loadsByName;
    expect(loads("val k = Class.forName(name)")).toBe(true);
    expect(loads('val k = Class.forName("splice.core.$name")')).toBe(true);
    expect(loads("val k = javaClass.classLoader.loadClass(name)")).toBe(true);
    expect(loads("val all = java.util.ServiceLoader.load(Runnable::class.java)")).toBe(true);
    expect(loads("val scan = ClassGraph().enableAllInfo().scan()")).toBe(true);
    expect(loads('val k = Class.forName("splice.core.Clock")')).toBe(false);
    expect(loads('// Class.forName(name) and ServiceLoader in a comment')).toBe(false);
    expect(loads('val s = "ServiceLoader"')).toBe(false);
  });
});

// ── Properties over generated repositories ──
//
// A seeded generator writes Kotlin sources whose references it knows: each file declares names in many syntactic forms, and names
// other files through imports, qualified names, string literals, templates, raw strings, backticks and annotations, surrounded by
// text a lexer can misread (comment openers inside strings, nested comments, quotes in char literals, braces in templates). It adds
// sealed families, top-level operators used unnamed, abstract test bases, laws, resources, files the scan cannot read, discovery
// code and classes a build script names. The oracle walks the references it wrote, plus the rules for what the analysis cannot
// see, and the gradle command the selection builds must run every test class the oracle reaches.

interface Rand {
  next(): number;
  int(n: number): number;
  pick<T>(xs: readonly T[]): T;
  chance(p: number): boolean;
}

function rand(seed: number): Rand {
  let state = seed >>> 0;
  const next = (): number => {
    state = (state + 0x6d2b79f5) >>> 0;
    let t = state;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
  const int = (n: number): number => Math.floor(next() * n);
  return {
    next,
    int,
    pick<T>(xs: readonly T[]): T {
      const x = xs[int(xs.length)];
      if (x === undefined) throw new Error("picked from nothing");
      return x;
    },
    chance: (p) => next() < p,
  };
}

/** Lines a Kotlin lexer can misread. Each is lexically valid Kotlin and names nothing a generated file declares. */
const NOISE: readonly string[] = [
  'val s = "/* not a comment"',
  'val s = "// not a comment either"',
  'val s = "a quote \\" inside"',
  "val c = '\"'",
  "val c = '\\''",
  "val c = '\\\\'",
  'val s = """raw " and "" and /* and // """',
  'val s = """ends in a quote""""',
  'val s = "${"}"}"',
  'val s = "${listOf("{", "}").size} braces"',
  "/* outer /* inner */ still outer */",
  '// a line comment with "a quote and /* an opener',
  'val s = "a dollar $ alone and \\${escaped}"',
  'val s = "a backslash \\\\"',
  "val n = 0x1F + 1_000L + 1.5e3.toInt()",
  'val s = "\\u0022 a unicode quote"',
  "val l = { x: Int -> x * 2 }",
  "val m = mapOf<String, List<Int>>()",
  'val s = """${"\\"\\"\\""}"""',
  "val r = 1..9",
];

type Decl = (name: string) => string;

/** Top-level declarations of a type [name], in the forms Kotlin allows. */
const TYPE_DECLS: readonly Decl[] = [
  (n) => `class ${n}`,
  (n) => `data class ${n}(val value: Int, val label: String = "x)")`,
  (n) => `object ${n} {\n    const val LIMIT = 3\n}`,
  (n) => `interface ${n} {\n    fun run(): Int\n}`,
  (n) => `enum class ${n} { ONE, TWO }`,
  (n) => `abstract class ${n}<T : Comparable<T>>(val seed: T)`,
  (n) => `fun interface ${n} {\n    fun call(x: Int): Int\n}`,
  (n) => `annotation class ${n}(val reason: String = "")`,
  (n) => `typealias ${n} = Map<String, List<Int>>`,
  (n) => `@Suppress("UNUSED", "a)(b")\nclass ${n}`,
  (n) => `internal class ${n} @JvmOverloads constructor(val x: Int = 0)`,
  (n) => `@JvmInline\nvalue class ${n}(val raw: Long)`,
  (n) => `sealed class ${n} {\n    object Only : ${n}()\n}`,
  (n) => `class \`${n}\``,
  (n) => `open class ${n} : Comparable<${n}> {\n    override fun compareTo(other: ${n}): Int = 0\n}`,
];

/** Top-level declarations of a function or property [name]. */
const VALUE_DECLS: readonly Decl[] = [
  (n) => `fun ${n}(x: Int): Int = x + 1`,
  (n) => `fun <T : Comparable<T>> List<T>.${n}(by: (T) -> Int = { 0 }): Map<String, List<T>> = emptyMap()`,
  (n) => `fun ((Int) -> Unit).${n}() = this(1)`,
  (n) => `val ${n}: Int = 42`,
  (n) => `val String.${n}: Int get() = length`,
  (n) => `val <T> List<T>.${n}: T?\n    get() = firstOrNull()`,
  (n) => `const val ${n} = "/* ${n} */"`,
  (n) => `val ${n} by lazy { 1 }`,
  (n) => `inline fun <reified T> ${n}(): String = T::class.java.name`,
  (n) => `infix fun Int.${n}(other: Int) = this + other`,
  (n) => `fun \`${n}\`() = Unit`,
  (n) => `suspend fun ${n}(block: suspend () -> Unit) = block()`,
  (n) => `fun String?.${n}(): Boolean = this == null`,
  (n) => `private fun helper() = 1\nfun ${n}() = helper()`,
];

/**
 * A reference to [n], declared in package [p], from a file in package [here]: the imports it needs and one statement. A form that
 * names [n] only inside a string is a reference to a type alone, as reflection reads it; nothing can reach a function that way.
 */
type Ref = (n: string, p: string, here: string) => { imports: string[]; code: string; stringOnly?: true };

const REFS: readonly Ref[] = [
  (n, p) => ({ imports: [`import ${p}.${n}`], code: `val r = listOf<Any?>(${n}::class)` }),
  (n, p) => ({ imports: [], code: `val r = ${p}.${n}::class` }),
  (n, p) => ({ imports: [], code: `val r = "${p}.${n}"`, stringOnly: true }),
  (n, p) => ({ imports: [`import ${p}.${n} as Alias`], code: "val r = Alias::class" }),
  (n, p) => ({ imports: [`import ${p}.*`], code: `val r = \`${n}\`` }),
  (n, p) => ({ imports: [`import ${p}.${n}`], code: `val r = "value: \${${n}}"` }),
  (n, p) => ({ imports: [`import ${p}.${n}`], code: `val r = "$${n}"` }),
  (n, p) => ({ imports: [], code: `val r = """raw \${${p}.${n}} """` }),
  (n, p) => ({ imports: [`import ${p}.${n}`], code: `@${n}("x") val r = 1` }),
  (n, p) => ({ imports: [], code: `val r: ${p}.${n}? = null` }),
  (n, p, here) => ({ imports: p === here ? [] : [`import ${p}.${n}`], code: `val r = ${n}` }),
  (n, p) => ({ imports: [], code: `val r = "\${ "${p}.${n}" }"`, stringOnly: true }),
];

/** Comments that mention [n] without referring to it. */
const DECOYS: readonly ((n: string) => string)[] = [
  (n) => `// ${n} is not used here`,
  (n) => `/* ${n} */`,
  (n) => `/** See [${n}]. */`,
  (n) => `/* outer /* ${n} */ ${n} */`,
];

interface Target {
  readonly name: string;
  readonly pkg: string;
}

/** A Kotlin source in package [pkg] that declares [decls] and refers to [refs], with noise and comments about [decoys]. */
function kotlinSource(r: Rand, pkg: string, decls: readonly string[], refs: readonly Target[], decoys: readonly string[], extra: readonly string[] = []): string {
  const imports = new Set<string>();
  const statements: string[] = [];
  for (const ref of refs) {
    if (r.chance(0.6)) statements.push(r.pick(NOISE));
    let form = r.pick(REFS)(ref.name, ref.pkg, pkg);
    while (form.stringOnly && /^[a-z]/.test(ref.name)) form = r.pick(REFS)(ref.name, ref.pkg, pkg);
    for (const line of form.imports) imports.add(line);
    statements.push(form.code);
  }
  for (const decoy of decoys) statements.push(r.pick(DECOYS)(decoy));
  statements.push(...extra);
  if (r.chance(0.5)) statements.push(r.pick(NOISE));
  return [`package ${pkg}`, "", ...imports, "", ...decls, "", "private fun uses() {", ...statements.map((s) => `    ${s}`), "}", ""].join("\n");
}

describe("the scan sees through generated sources", () => {
  test("every declared name is declared, every referenced name is a token, and a name only a comment mentions is not", () => {
    for (let seed = 1; seed <= 600; seed++) {
      const r = rand(seed);
      const pkg = r.pick(["p.a", "p.b", "p.shared"]);
      const types = Array.from({ length: 1 + r.int(2) }, (_, i) => `D${seed}x${i}`);
      const values = Array.from({ length: r.int(3) }, (_, i) => `d${seed}x${i}`);
      const decls = [...types.map((n) => r.pick(TYPE_DECLS)(n)), ...values.map((n) => r.pick(VALUE_DECLS)(n))];
      const refs = Array.from({ length: 1 + r.int(4) }, (_, i) => ({ name: `${r.chance(0.5) ? "R" : "r"}${seed}x${i}`, pkg: r.pick(["p.a", "p.c", pkg]) }));
      const decoys = Array.from({ length: r.int(3) }, (_, i) => `Z${seed}x${i}`);
      const text = kotlinSource(r, pkg, decls, refs, decoys);
      const scan = scanSource(text, `x/src/main/kotlin/${pkg.replaceAll(".", "/")}/F${seed}.kt`);
      const missing = [...types, ...values].filter((n) => !scan.declared.includes(n));
      const unseen = refs.map((ref) => ref.name).filter((n) => !scan.tokens.has(n));
      const leaked = decoys.filter((n) => scan.tokens.has(n));
      expect({ seed, parsed: scan.parsed, missing, unseen, leaked, packageName: scan.packageName }).toEqual({ seed, parsed: true, missing: [], unseen: [], leaked: [], packageName: pkg });
    }
  });
});

interface GenModule {
  readonly path: string;
  readonly dir: string;
  readonly pkg: string;
}

const GEN_MODULES: readonly GenModule[] = [
  { path: ":a", dir: "a", pkg: "p.a" },
  { path: ":a-inner", dir: "a/inner", pkg: "p.ai" },
  { path: ":b", dir: "libs/b", pkg: "p.b" },
  { path: ":c", dir: "c", pkg: "p.c" },
];

interface GenSource {
  readonly path: string;
  readonly module: GenModule;
  readonly test: boolean;
  readonly pkg: string;
  readonly names: readonly string[];
  /** The sources it really depends on. */
  readonly needs: readonly string[];
  /** The test class it holds, if any: JUnit runs it unless it is abstract, and lawTest runs it if it is a law. */
  readonly testClass?: { readonly fqn: string; readonly law: boolean; readonly runs: boolean };
  readonly text: string;
  readonly broken: boolean;
  readonly discovers: boolean;
}

interface GenRepo {
  readonly sources: GenSource[];
  readonly files: Record<string, string>;
  /** Resources and goldens, by module path. */
  readonly resources: Map<string, string[]>;
  /** The classes a module's build script names, by full name, with the module that names them. */
  readonly named: Map<string, string>;
  /** The modules whose test task the build logic depends on by name. */
  readonly whole: Set<string>;
}

const pathOf = (m: GenModule, test: boolean, pkg: string, file: string): string =>
  `${m.dir}/src/${test ? "test" : "main"}/kotlin/${pkg.replaceAll(".", "/")}/${file}.kt`;

function generateRepo(r: Rand): GenRepo {
  const sources: GenSource[] = [];
  let serial = 0;
  const pkgOf = (m: GenModule): string => (r.chance(0.2) ? "p.shared" : m.pkg);
  const mains = (): GenSource[] => sources.filter((s) => !s.test && s.names.length > 0);
  const pickTargets = (pool: readonly GenSource[], max: number): GenSource[] => {
    const picked = new Set<GenSource>();
    const count = pool.length === 0 ? 0 : r.int(max + 1);
    for (let i = 0; i < count; i++) picked.add(r.pick(pool));
    return [...picked];
  };
  const decoysFor = (): string[] => (r.chance(0.4) ? [`Unrelated${r.int(1000)}`] : []);
  const operators: GenSource[] = [];

  const mainSource = (m: GenModule, pkg: string, name: string, decl: string, targets: readonly GenSource[], extra: readonly string[] = [], extraNeeds: readonly string[] = []): GenSource => {
    const refs = targets.map((t) => ({ name: t.names[0] ?? "", pkg: t.pkg }));
    let text = kotlinSource(r, pkg, [decl], refs, decoysFor(), extra);
    const broken = r.chance(0.03);
    if (broken) text += "\n/* never closed\n";
    const discovers = extra.some((line) => line.includes("ServiceLoader") || line.includes("forName"));
    return { path: pathOf(m, false, pkg, name), module: m, test: false, pkg, names: [name], needs: [...targets.map((t) => t.path), ...extraNeeds], text, broken, discovers };
  };

  const mainCount = 8 + r.int(9);
  for (let i = 0; i < mainCount; i++) {
    const m = r.pick(GEN_MODULES);
    const pkg = pkgOf(m);
    const roll = r.next();
    if (roll < 0.12) {
      // A sealed family: the parent's users depend on every subclass file, which names the parent.
      const parent = `S${serial++}`;
      const subclasses = Array.from({ length: 1 + r.int(2) }, () => `S${serial++}`);
      const parentPath = pathOf(m, false, pkg, parent);
      const children = subclasses.map((child) => ({
        path: pathOf(m, false, pkg, child),
        module: m,
        test: false,
        pkg,
        names: [child],
        needs: [parentPath],
        text: kotlinSource(r, pkg, [`class ${child} : ${parent}`], [], decoysFor()),
        broken: false,
        discovers: false,
      }));
      sources.push({ path: parentPath, module: m, test: false, pkg, names: [parent], needs: children.map((c) => c.path), text: kotlinSource(r, pkg, [`sealed interface ${parent}`], [], []), broken: false, discovers: false }, ...children);
      continue;
    }
    if (roll < 0.22 && mains().length > 0) {
      // A top-level operator on an earlier type; its users write `a + b` and never name it.
      const operand = r.pick(mains());
      const name = `Ops${serial++}`;
      const text = kotlinSource(r, pkg, [`operator fun ${operand.pkg}.${operand.names[0]}.plus(other: ${operand.pkg}.${operand.names[0]}) = this`], [], decoysFor());
      const op: GenSource = { path: pathOf(m, false, pkg, name), module: m, test: false, pkg, names: ["plus"], needs: [operand.path], text, broken: false, discovers: false };
      sources.push(op);
      operators.push(op);
      continue;
    }
    const upper = r.chance(0.6);
    const name = `${upper ? "Q" : "q"}${serial++}`;
    const decl = r.pick(upper ? TYPE_DECLS : VALUE_DECLS)(name);
    const extra: string[] = [];
    const extraNeeds: string[] = [];
    if (r.chance(0.02)) extra.push(r.pick(["val loaded = java.util.ServiceLoader.load(Runnable::class.java).toList()", "val k = Class.forName(name)"]));
    let srcPkg = pkg;
    if (operators.length > 0 && r.chance(0.2)) {
      const op = r.pick(operators);
      if (r.chance(0.5)) srcPkg = op.pkg;
      else extra.push(`// uses ${op.pkg}.* below`);
      extra.push("val sum = left + right");
      extraNeeds.push(op.path);
      const source = mainSource(m, srcPkg, name, decl, pickTargets(mains(), 3), extra, extraNeeds);
      sources.push(srcPkg === op.pkg ? source : { ...source, text: source.text.replace("\n\n", `\n\nimport ${op.pkg}.*\n`) });
      continue;
    }
    sources.push(mainSource(m, srcPkg, name, decl, pickTargets(mains(), 3), extra, extraNeeds));
  }

  // Test helpers, then test classes: laws, abstract bases with a subclass, and plain tests.
  const helpers = new Map<string, GenSource[]>();
  for (const m of GEN_MODULES) {
    if (!r.chance(0.4)) continue;
    const pkg = pkgOf(m);
    const name = `H${serial++}`;
    const targets = pickTargets(mains(), 2);
    const helper: GenSource = {
      path: pathOf(m, true, pkg, name),
      module: m,
      test: true,
      pkg,
      names: [name],
      needs: targets.map((t) => t.path),
      text: kotlinSource(r, pkg, [`object ${name} {\n    fun make(): Any = 1\n}`], targets.map((t) => ({ name: t.names[0] ?? "", pkg: t.pkg })), decoysFor()),
      broken: false,
      discovers: false,
    };
    sources.push(helper);
    helpers.set(m.path, [helper]);
  }
  const testCount = 6 + r.int(9);
  for (let i = 0; i < testCount; i++) {
    const m = r.pick(GEN_MODULES);
    const pkg = pkgOf(m);
    const targets = [...pickTargets(mains(), 3), ...pickTargets(helpers.get(m.path) ?? [], 1)];
    const refs = targets.map((t) => ({ name: t.names[0] ?? "", pkg: t.pkg }));
    const law = r.chance(0.15);
    const abstractBase = !law && r.chance(0.12);
    const name = abstractBase ? `B${serial++}Test` : `T${serial++}Test`;
    const extra: string[] = [];
    const needs = targets.map((t) => t.path);
    if (operators.length > 0 && r.chance(0.15)) {
      const op = r.pick(operators);
      extra.push("val sum = left + right");
      needs.push(op.path);
      refs.push({ name: "*", pkg: op.pkg });
    }
    const body = kotlinSource(r, pkg, [], refs.filter((ref) => ref.name !== "*"), decoysFor(), extra);
    const wildcard = refs.filter((ref) => ref.name === "*").map((ref) => `import ${ref.pkg}.*`);
    const header = [
      "import org.junit.jupiter.api.Tag",
      "import org.junit.jupiter.api.Test",
      ...wildcard,
      ...(law ? ['@Tag("law")'] : []),
      `${abstractBase ? "abstract " : ""}class ${name} {`,
      "    @Test",
      "    fun works() {",
      "        check(true)",
      "    }",
      "}",
    ].join("\n");
    const text = body.replace("\n\n", `\n\n${header}\n\n`);
    const fqn = `${pkg}.${name}`;
    const source: GenSource = { path: pathOf(m, true, pkg, name), module: m, test: true, pkg, names: [name], needs, testClass: { fqn, law, runs: !abstractBase }, text, broken: false, discovers: false };
    sources.push(source);
    if (abstractBase) {
      const child = `T${serial++}Test`;
      const childPkg = r.chance(0.5) ? pkg : m.pkg;
      const childText = kotlinSource(r, childPkg, [`class ${child} : ${pkg}.${name}()`], [], decoysFor());
      sources.push({ path: pathOf(m, true, childPkg, child), module: m, test: true, pkg: childPkg, names: [child], needs: [source.path], testClass: { fqn: `${childPkg}.${child}`, law: false, runs: true }, text: childText, broken: false, discovers: false });
    }
  }

  const files: Record<string, string> = { "settings.gradle.kts": 'rootProject.name = "gen"\n', "build.gradle.kts": "plugins { base }\n" };
  const named = new Map<string, string>();
  const whole = new Set<string>();
  for (const m of GEN_MODULES) {
    let script = 'plugins { id("splice.kotlin-common") }\n';
    const types = mains().filter((s) => /^[QS]/.test(s.names[0] ?? ""));
    if (types.length > 0 && r.chance(0.15)) {
      const target = r.pick(types);
      const fqn = `${target.pkg}.${target.names[0]}`;
      script += `\ntasks.test {\n    systemProperty("splice.hook", "${fqn}")\n}\n`;
      named.set(fqn, m.path);
    }
    files[`${m.dir}/build.gradle.kts`] = script;
  }
  if (r.chance(0.3)) {
    const m = r.pick(GEN_MODULES);
    whole.add(m.path);
    files["build-logic/src/main/kotlin/splice.ladder.gradle.kts"] = `tasks.register("lawSuites") {\n    dependsOn("${m.path}:test")\n}\n`;
  }
  const resources = new Map<string, string[]>();
  for (const m of GEN_MODULES) {
    const list: string[] = [];
    if (r.chance(0.5)) list.push(`${m.dir}/src/main/resources/r${serial++}.properties`);
    if (r.chance(0.5)) list.push(`${m.dir}/src/test/resources/goldens/g${serial++}.json`);
    for (const path of list) files[path] = "x = 1\n";
    resources.set(m.path, list);
  }
  for (const source of sources) files[source.path] = source.text;
  return { sources, files, resources, named, whole };
}

interface GenPush {
  readonly edits: Record<string, string | null>;
  /** Sources the push adds. */
  readonly added: GenSource[];
}

function generatePush(r: Rand, repo: GenRepo): GenPush {
  const edits: Record<string, string | null> = {};
  const added: GenSource[] = [];
  const count = 1 + r.int(4);
  for (let i = 0; i < count; i++) {
    const roll = r.next();
    const source = r.pick(repo.sources);
    if (roll < 0.35) edits[source.path] = `${repo.files[source.path] ?? ""}\n// edited\n`;
    else if (roll < 0.45 && !source.test && /^[QS]/.test(source.names[0] ?? "")) {
      // A rename in the file alone: its dependents still name the old name, and only the push's own build can say so.
      const old = source.names[0] ?? "";
      edits[source.path] = (repo.files[source.path] ?? "").replaceAll(new RegExp(`\\b${old}\\b`, "g"), `${old}Renamed`);
    } else if (roll < 0.55) edits[source.path] = null;
    else if (roll < 0.7) {
      const m = r.pick(GEN_MODULES);
      const resources = repo.resources.get(m.path) ?? [];
      if (resources.length > 0) edits[r.pick(resources)] = "x = 2\n";
      else edits[`${m.dir}/src/main/resources/added${r.int(1000)}.txt`] = "new\n";
    } else if (roll < 0.85) {
      const m = r.pick(GEN_MODULES);
      const name = `N${r.int(100000)}Test`;
      const targets = repo.sources.filter((s) => !s.test && s.names.length > 0);
      const target = targets.length > 0 ? r.pick(targets) : undefined;
      const text = [
        `package ${m.pkg}`,
        "",
        "import org.junit.jupiter.api.Test",
        ...(target ? [`import ${target.pkg}.${target.names[0]}`] : []),
        "",
        `class ${name} {`,
        "    @Test",
        "    fun works() {",
        `        check(${target ? `${target.names[0]}::class` : "true"} != null)`,
        "    }",
        "}",
        "",
      ].join("\n");
      const path = pathOf(m, true, m.pkg, name);
      edits[path] = text;
      added.push({ path, module: m, test: true, pkg: m.pkg, names: [name], needs: target ? [target.path] : [], testClass: { fqn: `${m.pkg}.${name}`, law: false, runs: true }, text, broken: false, discovers: false });
    } else edits[r.pick(["docs/notes.md", "tools/gate/src/x.ts", "README.md"])] = "changed\n";
  }
  return { edits, added };
}

const inModule = (path: string): GenModule | undefined =>
  [...GEN_MODULES].sort((x, y) => y.dir.length - x.dir.length).find((m) => path.startsWith(`${m.dir}/`));

/** The test classes the push can affect, as "module fqn": the references the generator wrote, and the rules for the rest. */
function oracle(repo: GenRepo, push: GenPush): Set<string> {
  const changed = Object.keys(push.edits);
  const deleted = new Set(changed.filter((path) => push.edits[path] === null));
  const all = [...repo.sources, ...push.added];
  const tip = all.filter((s) => !deleted.has(s.path));
  const dependents = new Map<string, string[]>();
  for (const s of all) for (const need of s.needs) dependents.set(need, [...(dependents.get(need) ?? []), s.path]);

  // A module runs whole when the analysis cannot see into something of it; when that something is not test-only, the tests in other
  // modules that reach the module run too. Other modules never see a module's test sources or test resources.
  const wholeModules = new Set<string>();
  const reachModules = new Set<string>();
  const unsure = (module: string, testOnly: boolean): void => {
    wholeModules.add(module);
    if (!testOnly) reachModules.add(module);
  };
  const sourceChanged = changed.some((path) => path.endsWith(".kt"));
  for (const path of changed) {
    const m = inModule(path);
    if (m && /\/src\/[^/]+\/resources\//.test(path)) unsure(m.path, path.startsWith(`${m.dir}/src/test/`));
  }
  for (const s of tip) {
    if (s.broken && changed.some((path) => inModule(path) !== undefined)) unsure(s.module.path, s.test);
    if (s.discovers && sourceChanged) unsure(s.module.path, s.test);
  }
  for (const s of repo.sources) {
    if (!changed.includes(s.path)) continue;
    for (const name of s.names) {
      const naming = repo.named.get(`${s.pkg}.${name}`);
      if (naming) {
        unsure(naming, false);
        unsure(s.module.path, s.test);
      }
    }
  }

  const reached = new Set<string>(changed.filter((path) => all.some((s) => s.path === path)));
  for (const s of all) if (!s.test && reachModules.has(s.module.path)) reached.add(s.path);
  const queue = [...reached];
  while (queue.length > 0) {
    const path = queue.pop() ?? "";
    for (const dependent of dependents.get(path) ?? []) {
      if (reached.has(dependent)) continue;
      reached.add(dependent);
      queue.push(dependent);
    }
  }
  const required = new Set<string>();
  for (const s of tip) {
    if (!s.testClass || s.testClass.law || !s.testClass.runs) continue;
    if (reached.has(s.path) || wholeModules.has(s.module.path)) required.add(`${s.module.path} ${s.testClass.fqn}`);
  }
  return required;
}

function inputOf(repo: GenRepo, push: GenPush): ImpactInput {
  const tip: Record<string, string> = { ...repo.files };
  for (const [path, text] of Object.entries(push.edits)) {
    if (text === null) delete tip[path];
    else tip[path] = text;
  }
  return {
    modules: GEN_MODULES.map((m) => ({ path: m.path, dir: m.dir })),
    changed: Object.keys(push.edits).sort(),
    tree: {
      paths: Object.keys(tip).sort(),
      read: (path) => {
        const text = tip[path];
        if (text === undefined) throw new Error(`${path} is not in the pushed commit`);
        return text;
      },
      before: (path) => repo.files[path],
    },
  };
}

/**
 * What gradle runs for a pre-push whose scope checks [checked] and requests lawSuites, plus [args]: whether a test class runs, and
 * whether a module's test sources compile. lawSuites depends on the test task of each [whole] module by name.
 */
function gradle(args: { tasks: string[]; options: string[] }, checked: readonly string[], whole: ReadonlySet<string>) {
  const requested = new Set(checked.map((m) => `${m}:check`));
  const filters = new Map<string, string[]>();
  let last: string | undefined;
  for (const token of args.tasks) {
    if (token.startsWith("--tests=")) {
      if (last === undefined || !last.endsWith(":test")) throw new Error(`${token} follows ${last ?? "nothing"}, not a test task`);
      filters.set(last, [...(filters.get(last) ?? []), token.slice("--tests=".length)]);
    } else if (token.startsWith("-")) throw new Error(`${token} is an option among the tasks`);
    else {
      requested.add(token);
      last = token;
    }
  }
  const excluded = new Set<string>();
  for (const option of args.options) {
    if (!option.startsWith("--exclude-task=")) throw new Error(`unexpected option ${option}`);
    excluded.add(option.slice("--exclude-task=".length));
  }
  const testRuns = (module: string): boolean =>
    !excluded.has(`${module}:test`) && (requested.has(`${module}:test`) || requested.has(`${module}:check`) || whole.has(module));
  const matches = (pattern: string, fqn: string): boolean => new RegExp(`^${pattern.replaceAll(".", "\\.").replaceAll("*", ".*")}$`).test(fqn);
  return {
    runs: (module: string, fqn: string): boolean => {
      if (!testRuns(module)) return false;
      const patterns = filters.get(`${module}:test`) ?? [];
      return patterns.length === 0 || patterns.some((p) => matches(p, fqn));
    },
    compiles: (module: string): boolean => testRuns(module) || requested.has(`${module}:testClasses`),
    excluded,
  };
}

describe("the selection skips no test the change can affect", () => {
  test("over generated repositories and pushes, every test class the change reaches in a checked module runs, and every checked module's tests compile", () => {
    let narrowed = 0;
    for (let seed = 1; seed <= 400; seed++) {
      const r = rand(seed * 7919);
      const repo = generateRepo(r);
      const push = generatePush(r, repo);
      const input = inputOf(repo, push);
      const checked = GEN_MODULES.filter(() => r.chance(0.5)).map((m) => m.path);
      const impact = confineTo(testImpact(input), checked);
      const args = testArgs(impact, checked, GEN_MODULES.map((m) => m.path));
      const run = gradle(args, checked, repo.whole);
      // A module the scope left out runs no test on this push, and the selection never adds one: only a module the scope checks, or
      // one whose test task lawSuites names, is judged here.
      const judged = (module: string): boolean => checked.includes(module) || repo.whole.has(module);
      const widened = args.tasks.filter((token) => !token.startsWith("-") && !judged(token.slice(0, token.lastIndexOf(":"))));
      const skipped = [...oracle(repo, push)].filter((entry) => {
        const [module = "", fqn = ""] = entry.split(" ");
        return judged(module) && !run.runs(module, fqn);
      });
      const uncompiled = checked.filter((path) => {
        const module = GEN_MODULES.find((m) => m.path === path);
        const hasTests = input.tree.paths.some((p) => p.startsWith(`${module?.dir}/src/test/kotlin/`) && inModule(p) === module);
        return hasTests && !run.compiles(path);
      });
      const wholeTouched = [...repo.whole].filter((m) => run.excluded.has(`${m}:test`));
      expect({ seed, edits: Object.keys(push.edits), skipped, uncompiled, wholeTouched, widened }).toEqual({
        seed,
        edits: Object.keys(push.edits),
        skipped: [],
        uncompiled: [],
        wholeTouched: [],
        widened: [],
      });
      const tipClasses = [...repo.sources, ...push.added].filter(
        (s) => input.tree.paths.includes(s.path) && s.testClass?.runs && !s.testClass.law && judged(s.module.path),
      );
      if (tipClasses.some((s) => !run.runs(s.module.path, s.testClass?.fqn ?? ""))) narrowed++;
    }
    // The property must also leave room to narrow: a selection that always ran everything would pass it trivially.
    expect(narrowed).toBeGreaterThan(100);
  });

  test("a push that holds a fallback trigger runs the full suite, whatever else it changes", () => {
    const triggers: readonly ((m: GenModule) => string)[] = [
      (m) => `${m.dir}/build.gradle.kts`,
      () => "build.gradle.kts",
      () => "settings.gradle.kts",
      (m) => `${m.dir}/build.gradle`,
      () => "settings.gradle",
      () => "gradle/libs.versions.toml",
      () => "gradle/wrapper/gradle-wrapper.properties",
      (m) => `${m.dir}/gradle/extra.txt`,
      () => "gradle.properties",
      (m) => `${m.dir}/gradle.properties`,
      () => "gradlew",
      () => "gradlew.bat",
      () => "catalogs/tools.versions.toml",
      () => "build-logic/src/main/kotlin/splice.x.gradle.kts",
      () => "build-logic/build.gradle.kts",
      () => "buildSrc/src/main/kotlin/X.kt",
      () => "quality/compiler-plugin/src/main/kotlin/splice/plugin/X.kt",
      () => "package.json",
      (m) => `${m.dir}/src/testFixtures/kotlin/p/Fx.kt`,
      (m) => `${m.dir}/src/lawFixtures/kotlin/p/Lx.kt`,
      (m) => `${m.dir}/src/testFixtures/resources/x.json`,
    ];
    for (let seed = 1; seed <= 200; seed++) {
      const r = rand(seed * 104729);
      const repo = generateRepo(r);
      const push = generatePush(r, repo);
      const trigger = r.pick(triggers)(r.pick(GEN_MODULES));
      const edits = { ...push.edits, [trigger]: r.chance(0.2) ? null : "changed\n" };
      const impact = testImpact(inputOf(repo, { edits, added: push.added }));
      const checked = GEN_MODULES.filter(() => r.chance(0.5)).map((m) => m.path);
      const args = testArgs(impact, checked, GEN_MODULES.map((m) => m.path));
      const unchecked = GEN_MODULES.map((m) => m.path).filter((m) => !checked.includes(m));
      expect({ seed, trigger, mode: impact.mode, args }).toEqual({ seed, trigger, mode: "full", args: { tasks: unchecked.map((m) => `${m}:check`), options: [] } });
    }
  });
});
