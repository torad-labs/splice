// A version catalog change is scoped to the modules whose build scripts read the changed entries (Marcos, Nov 2 2026: "can we
// make sure that the pre push only runs the diff please, that is change aware. Let's make it scoped"). Until then every catalog
// change ran every module's check and the full unit suite: on Nov 2 a guava floor two scripts read, a logback floor, two
// Dependabot bumps and a konsist hold each cost a 15 to 21 minute full run.
import { describe, expect, test } from "bun:test";
import { catalogChanges, catalogImpact, catalogImpactOf, catalogReferences, parseCatalog } from "../src/lib/catalog-scope.ts";
import { loadScope } from "../src/lib/scope.ts";

const CATALOG = [
  "# the gateway's catalog",
  "[versions]",
  'kotlin = "2.3.21"',
  'serialization = "1.11.0"',
  'guava = "33.7.1-jre"',
  'ktlint = "1.8.0"',
  'grpc = "1.85.1"',
  "",
  "[libraries]",
  'kotlinx-serialization-json = { module = "org.jetbrains.kotlinx:kotlinx-serialization-json", version.ref = "serialization" }',
  'grpc-netty-shaded = { module = "io.grpc:grpc-netty-shaded", version.ref = "grpc" }',
  'zstd-jni = { module = "com.github.luben:zstd-jni", version = "1.5.7-24" }',
  'kotlin-compiler-embeddable = { module = "org.jetbrains.kotlin:kotlin-compiler-embeddable", version.ref = "kotlin" }',
  'junit-jupiter = { module = "org.junit.jupiter:junit-jupiter" }',
  "",
  "[bundles]",
  'grpc = ["grpc-netty-shaded", "zstd-jni"]',
  "",
  "[plugins]",
  'kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }',
  'ktlint = { id = "org.jlleitschuh.gradle.ktlint", version.ref = "ktlint" }',
  "",
].join("\n");

/** [catalog] with one line replaced. */
const edited = (from: string, to: string, catalog: string = CATALOG): string => {
  if (!catalog.includes(from)) throw new Error(`fixture has no line ${from}`);
  return catalog.replace(from, to);
};

const TEXTS = [
  { path: "build.gradle.kts", module: undefined, text: 'plugins { alias(libs.plugins.kotlin.jvm) apply false }\nallprojects { group = "splice" }\n' },
  { path: "settings.gradle.kts", module: undefined, text: 'rootProject.name = "splice"\ninclude(":core")\n' },
  {
    path: "build-logic/build.gradle.kts",
    module: undefined,
    text: 'plugins { alias(libs.plugins.ktlint) }\ndependencies { implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:${libs.versions.kotlin.get()}") }\n',
  },
  {
    path: "build-logic/src/main/kotlin/splice.kotlin-common.gradle.kts",
    module: undefined,
    text: 'private val catalog = CatalogReader(extensions.getByType<VersionCatalogsExtension>().named("libs"))\nconfigure<KtlintExtension> { version.set(catalog.version("ktlint")) }\n',
  },
  { path: "core/build.gradle.kts", module: ":core", text: 'plugins { id("splice.kotlin-common") }\ndependencies { api(libs.kotlinx.serialization.json) }\n' },
  {
    path: "integrations/codemode/build.gradle.kts",
    module: ":integrations-codemode",
    text: 'dependencies {\n    implementation(libs.bundles.grpc)\n    constraints { implementation("com.google.guava:guava:${libs.versions.guava.get()}") }\n}\n',
  },
  {
    path: "app/build.gradle.kts",
    module: ":app",
    text: 'buildscript { dependencies { classpath("com.google.guava:guava:${libs.versions.guava.get()}") } }\ndependencies { implementation(libs.kotlinx.serialization.json) }\n',
  },
  { path: "quality/compiler-plugin/build.gradle.kts", module: ":quality-compiler-plugin", text: "dependencies { compileOnly(libs.kotlin.compiler.embeddable) }\n" },
] as const;

const impact = (after: string, before: string = CATALOG) =>
  catalogImpact({ path: "gradle/libs.versions.toml", accessor: "libs", before, after, texts: TEXTS });

const modulesOf = (result: ReturnType<typeof catalogImpact>): Record<string, string> => {
  if (result.kind !== "modules") throw new Error(`not a module selection: ${JSON.stringify(result)}`);
  return Object.fromEntries(result.modules);
};

describe("the catalog is read as the four tables Gradle reads", () => {
  test("versions, libraries, plugins and bundles, each by alias", () => {
    const catalog = parseCatalog(CATALOG);
    expect(catalog).toBeDefined();
    expect([...(catalog?.versions.keys() ?? [])]).toEqual(["kotlin", "serialization", "guava", "ktlint", "grpc"]);
    expect([...(catalog?.libraries.keys() ?? [])]).toContain("grpc-netty-shaded");
    expect([...(catalog?.plugins.keys() ?? [])]).toEqual(["kotlin-jvm", "ktlint"]);
    expect([...(catalog?.bundles.keys() ?? [])]).toEqual(["grpc"]);
  });

  test("a catalog that is not TOML is not read", () => {
    expect(parseCatalog("[versions\nkotlin = ")).toBeUndefined();
  });

  test("the changed entries are the aliases whose value differs, added or removed, and a comment changes none", () => {
    const before = parseCatalog(CATALOG);
    const bumped = parseCatalog(edited('guava = "33.7.1-jre"', 'guava = "33.8.0-jre"'));
    const commented = parseCatalog(edited("# the gateway's catalog", "# the gateway's catalog, verified 2026-11-02"));
    // the new alias goes inside the existing table: a second [versions] table is not TOML, and would not parse
    const added = parseCatalog(edited('grpc = "1.85.1"', 'grpc = "1.85.1"\nlogback = "1.5.34"'));
    if (!before || !bumped || !commented || !added) throw new Error("fixtures did not parse");
    expect(catalogChanges(before, bumped)).toEqual([{ section: "versions", alias: "guava" }]);
    expect(catalogChanges(before, commented)).toEqual([]);
    expect(catalogChanges(bumped, before)).toEqual([{ section: "versions", alias: "guava" }]);
    const removed = parseCatalog(edited('zstd-jni = { module = "com.github.luben:zstd-jni", version = "1.5.7-24" }', ""));
    if (!removed) throw new Error("fixture did not parse");
    expect(catalogChanges(before, removed)).toEqual([{ section: "libraries", alias: "zstd-jni" }]);
    expect(catalogChanges(before, added)).toEqual([{ section: "versions", alias: "logback" }]);
  });
});

describe("a build script's catalog references", () => {
  const catalog = parseCatalog(CATALOG);
  if (!catalog) throw new Error("fixture did not parse");
  const refs = (text: string) => catalogReferences(text, "libs", [catalog]);

  test("a typed accessor names its alias, with the dashes Gradle turns into dots, in every table", () => {
    expect(refs("implementation(libs.kotlinx.serialization.json)")).toEqual([{ section: "libraries", alias: "kotlinx-serialization-json" }]);
    expect(refs("implementation(libs.grpc.netty.shaded)")).toEqual([{ section: "libraries", alias: "grpc-netty-shaded" }]);
    expect(refs('classpath("com.google.guava:guava:${libs.versions.guava.get()}")')).toEqual([{ section: "versions", alias: "guava" }]);
    expect(refs("alias(libs.plugins.kotlin.jvm) apply false")).toEqual([{ section: "plugins", alias: "kotlin-jvm" }]);
    expect(refs("implementation(libs.bundles.grpc)")).toEqual([{ section: "bundles", alias: "grpc" }]);
    expect(refs("version.set(libs.versions.ktlint.asProvider())")).toEqual([{ section: "versions", alias: "ktlint" }]);
  });

  test("a lookup by literal name reads its alias too, and a mention of the file or an unknown alias reads nothing", () => {
    expect(refs('version.set(catalog.version("ktlint"))')).toEqual([{ section: "versions", alias: "ktlint" }]);
    expect(refs('libs.findLibrary("grpc-netty-shaded").get()')).toEqual([{ section: "libraries", alias: "grpc-netty-shaded" }]);
    expect(refs('libs.findPlugin("kotlin-jvm")')).toEqual([{ section: "plugins", alias: "kotlin-jvm" }]);
    expect(refs('from(files("../gradle/libs.versions.toml"))')).toEqual([]);
    expect(refs("implementation(libs.nothing.named.this)")).toEqual([]);
    expect(refs("val mylibs = 1; mylibs.versions")).toEqual([]);
  });

  test("a reference to an alias only the other side of the push knows is still a reference", () => {
    const after = parseCatalog(edited('grpc = "1.85.1"', 'grpc = "1.85.1"\nlogback = "1.5.34"'));
    if (!after) throw new Error("fixture did not parse");
    expect(refs('catalog.version("logback")')).toEqual([]);
    expect(catalogReferences('catalog.version("logback")', "libs", [catalog, after])).toEqual([{ section: "versions", alias: "logback" }]);
  });
});

describe("what a catalog change makes the pre-push run", () => {
  test("an alias two tables share is placed by table: a read of the library does not place a change to the version of the same name", () => {
    // the real catalog has ktlint, detekt, kover, cyclonedx, shadow and konsist in two tables each; here `foo` is a version no script
    // reads beside a library :core reads, and both change
    const both = edited('grpc = "1.85.1"', 'grpc = "1.85.1"\nfoo = "1.0"').replace(
      'junit-jupiter = { module = "org.junit.jupiter:junit-jupiter" }',
      'junit-jupiter = { module = "org.junit.jupiter:junit-jupiter" }\nfoo = { module = "g:foo", version = "2.0" }',
    );
    const bumped = both.replace('foo = "1.0"', 'foo = "1.1"').replace('version = "2.0"', 'version = "2.1"');
    const texts = TEXTS.map((text) => (text.path === "core/build.gradle.kts" ? { ...text, text: "dependencies { implementation(libs.foo) }\n" } : text));
    const result = catalogImpact({ path: "gradle/libs.versions.toml", accessor: "libs", before: both, after: bumped, texts });
    expect(result.kind).toBe("full");
    if (result.kind === "full") expect(result.why).toContain("`foo` in gradle/libs.versions.toml changed, and no build script reads it");
    // the library alone, read by :core, is placed
    const libraryOnly = catalogImpact({ path: "gradle/libs.versions.toml", accessor: "libs", before: both, after: both.replace('version = "2.0"', 'version = "2.1"'), texts });
    expect(Object.keys(modulesOf(libraryOnly))).toEqual([":core"]);
  });

  test("an entry only module scripts read selects those modules, and the reason names the entry and the script", () => {
    const result = impact(edited('guava = "33.7.1-jre"', 'guava = "33.8.0-jre"'));
    expect(Object.keys(modulesOf(result)).sort()).toEqual([":app", ":integrations-codemode"]);
    expect(modulesOf(result)[":integrations-codemode"]).toContain("`guava`");
    expect(modulesOf(result)[":integrations-codemode"]).toContain("integrations/codemode/build.gradle.kts");
  });

  test("a version a library pins by version.ref selects the modules that read the library, through it", () => {
    const result = impact(edited('serialization = "1.11.0"', 'serialization = "1.11.1"'));
    expect(Object.keys(modulesOf(result)).sort()).toEqual([":app", ":core"]);
    expect(modulesOf(result)[":core"]).toContain("`serialization`");
    expect(modulesOf(result)[":core"]).toContain("kotlinx-serialization-json");
  });

  test("a library a bundle carries selects the modules that read the bundle", () => {
    const result = impact(edited('zstd-jni = { module = "com.github.luben:zstd-jni", version = "1.5.7-24" }', 'zstd-jni = { module = "com.github.luben:zstd-jni", version = "1.5.7-25" }'));
    expect(Object.keys(modulesOf(result))).toEqual([":integrations-codemode"]);
  });

  test("an entry build-logic, the root build or settings reads is every module's, and the reason says which script", () => {
    const ktlint = impact(edited('ktlint = "1.8.0"', 'ktlint = "1.8.1"'));
    expect(ktlint.kind).toBe("full");
    if (ktlint.kind === "full") {
      expect(ktlint.why).toContain("`ktlint`");
      expect(ktlint.why).toContain("build-logic/");
    }
    const kotlin = impact(edited('kotlin = "2.3.21"', 'kotlin = "2.4.20"'));
    expect(kotlin.kind).toBe("full");
    if (kotlin.kind === "full") expect(kotlin.why).toContain("`kotlin`");
  });

  test("an entry read by a script that is itself every module's input is every module's, and the reason names the script", () => {
    // quality/compiler-plugin/build.gradle.kts builds the jar every module's compileKotlin loads (the root build wires -Xplugin), an edge
    // the module graph does not carry: on the real tree a kotlin-compiler-embeddable bump checked that one module of 35 (review of PR 445)
    const bumped = edited(
      'kotlin-compiler-embeddable = { module = "org.jetbrains.kotlin:kotlin-compiler-embeddable", version.ref = "kotlin" }',
      'kotlin-compiler-embeddable = { module = "org.jetbrains.kotlin:kotlin-compiler-embeddable", version = "2.4.0" }',
    );
    const everyModule = (path: string): boolean => path === "quality/compiler-plugin/build.gradle.kts";
    const result = catalogImpact({ path: "gradle/libs.versions.toml", accessor: "libs", before: CATALOG, after: bumped, texts: TEXTS, everyModule });
    expect(result.kind).toBe("full");
    if (result.kind === "full") {
      expect(result.why).toContain("`kotlin-compiler-embeddable`");
      expect(result.why).toContain("quality/compiler-plugin/build.gradle.kts");
      expect(result.why).toContain("as every module's build does");
    }
    // told nothing about the script, the entry scopes to the module that owns it
    expect(Object.keys(modulesOf(impact(bumped)))).toEqual([":quality-compiler-plugin"]);
  });

  test("an entry no build script reads cannot be placed, so every module runs and the reason says so", () => {
    const result = impact(edited('junit-jupiter = { module = "org.junit.jupiter:junit-jupiter" }', 'junit-jupiter = { module = "org.junit.jupiter:junit-jupiter", version = "6.1.3" }'));
    expect(result.kind).toBe("full");
    if (result.kind === "full") {
      expect(result.why).toContain("`junit-jupiter`");
      expect(result.why).toContain("no build script reads it");
    }
  });

  test("a change that moves no entry, such as a comment, selects nothing through the catalog", () => {
    expect(impact(edited("# the gateway's catalog", "# the gateway's catalog, verified 2026-11-02"))).toEqual({ kind: "none" });
  });

  test("a catalog the push adds, removes or breaks is every module's, and the reason says why", () => {
    const added = catalogImpact({ path: "gradle/libs.versions.toml", accessor: "libs", before: undefined, after: CATALOG, texts: TEXTS });
    expect(added.kind).toBe("full");
    if (added.kind === "full") expect(added.why).toContain("added");
    const broken = impact("[versions\nkotlin = ");
    expect(broken.kind).toBe("full");
    if (broken.kind === "full") expect(broken.why).toContain("TOML");
    const removed = catalogImpact({ path: "gradle/libs.versions.toml", accessor: "libs", before: CATALOG, after: undefined, texts: TEXTS });
    expect(removed.kind).toBe("full");
    if (removed.kind === "full") expect(removed.why).toContain("gradle/libs.versions.toml");
  });

  test("two changed entries with module readers select the union, and one every module reads wins", () => {
    const two = impact(edited('serialization = "1.11.0"', 'serialization = "1.11.1"', edited('guava = "33.7.1-jre"', 'guava = "33.8.0-jre"')));
    expect(Object.keys(modulesOf(two)).sort()).toEqual([":app", ":core", ":integrations-codemode"]);
    const wide = impact(edited('kotlin = "2.3.21"', 'kotlin = "2.4.20"', edited('guava = "33.7.1-jre"', 'guava = "33.8.0-jre"')));
    expect(wide.kind).toBe("full");
  });
});

describe("the pushed paths decide whether the catalog is read at all", () => {
  const scope = loadScope(
    JSON.stringify({
      full: [{ glob: "build.gradle.kts", why: "is the root build" }],
      docs: [],
      laws: [],
      catalog: [{ glob: "gradle/libs.versions.toml", accessor: "libs", why: "is the version catalog" }],
    }),
  );
  const tip: Record<string, string> = { "gradle/libs.versions.toml": edited('guava = "33.7.1-jre"', 'guava = "33.8.0-jre"') };

  test("a push that does not change the catalog reads nothing", () => {
    expect(catalogImpactOf({ changed: ["core/src/main/kotlin/A.kt"], scope, read: (path) => tip[path], before: () => CATALOG, texts: () => TEXTS })).toBeUndefined();
  });

  test("a push that changes the catalog is read against the base, over the build texts the caller hands in", () => {
    let readTexts = 0;
    const result = catalogImpactOf({
      changed: ["gradle/libs.versions.toml", "core/src/main/kotlin/A.kt"],
      scope,
      read: (path) => tip[path],
      before: (path) => (path === "gradle/libs.versions.toml" ? CATALOG : undefined),
      texts: () => {
        readTexts++;
        return TEXTS;
      },
    });
    expect(result?.kind).toBe("modules");
    expect(readTexts).toBe(1);
  });

  test("a build script the scope marks full reads for every module: a catalog entry only it reads runs everything", () => {
    const bumped = edited(
      'kotlin-compiler-embeddable = { module = "org.jetbrains.kotlin:kotlin-compiler-embeddable", version.ref = "kotlin" }',
      'kotlin-compiler-embeddable = { module = "org.jetbrains.kotlin:kotlin-compiler-embeddable", version = "2.4.0" }',
    );
    const marked = loadScope(
      JSON.stringify({
        full: [{ glob: "quality/compiler-plugin/build.gradle.kts", why: "builds the compiler plugin jar every module compiles with" }],
        docs: [],
        laws: [],
        catalog: [{ glob: "gradle/libs.versions.toml", accessor: "libs", why: "is the version catalog" }],
      }),
    );
    const everything = catalogImpactOf({ changed: ["gradle/libs.versions.toml"], scope: marked, read: () => bumped, before: () => CATALOG, texts: () => TEXTS });
    expect(everything?.kind).toBe("full");
    if (everything?.kind === "full") expect(everything.why).toContain("quality/compiler-plugin/build.gradle.kts");
    // under a scope that does not mark the script, the same push scopes to the script's module
    const narrow = catalogImpactOf({ changed: ["gradle/libs.versions.toml"], scope, read: () => bumped, before: () => CATALOG, texts: () => TEXTS });
    expect(narrow?.kind).toBe("modules");
  });

  test("the committed scope file marks the catalog as a catalog, read under the `libs` accessor, and no longer as full", () => {
    const committed = loadScope();
    expect(committed.catalog.map((rule) => [rule.glob, rule.accessor])).toEqual([["gradle/libs.versions.toml", "libs"]]);
    expect(committed.full.some((rule) => rule.glob === "gradle/libs.versions.toml")).toBe(false);
    // the compiler plugin's script decides the jar every module compiles with, like its src/main
    expect(committed.full.some((rule) => rule.glob === "quality/compiler-plugin/build.gradle.kts")).toBe(true);
  });
});
