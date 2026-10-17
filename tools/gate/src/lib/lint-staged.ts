// NEW: Oct 10, 2026 — ktlint and detekt over the files a commit stages, run as plain java processes.
//
// Five pushes in one afternoon failed on formatting and size findings in files just committed, because pre-commit ran the
// walls and nothing else: each miss cost a five to seventeen minute pre-push round. This puts the two linters in the
// commit's own tier. Gradle cannot do it: a build that must wait for the slot cannot promise the hook's one minute.
//
// WHAT RUNS. The release jars of the versions the catalog names (gradle/libs.versions.toml), each checked against a
// sha256 written here before it is run. The jar is fetched once into the user's cache; a version the catalog names that
// has no digest here refuses the commit and says where to add it, so the linters can never drift from the catalog
// unseen. ktlint reads the root .editorconfig, copied beside the mirrored files; detekt reads quality/detekt/detekt.yml
// and builds upon its defaults, as the gradle task does. Both judge the STAGED bytes (the mirror), run side by side, and
// change no file.
//
// WHAT THIS DOES NOT COVER. The full-module run stays in pre-push. Detekt here has no type resolution and sees one
// commit's files, exactly as the gradle `detekt` task has no type resolution; a rule that needs another file is pre-push's.
import { spawn } from "node:child_process";
import { createHash } from "node:crypto";
import { chmodSync, copyFileSync, existsSync, mkdirSync, readFileSync, renameSync, writeFileSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";
import { resolveJdk21 } from "./jdk.ts";

export interface LintFinding {
  readonly tool: "ktlint" | "detekt";
  readonly line: string;
}

/** The verdict of the linters over the mirror: the findings, or the reason they could not judge. */
export type LintVerdict = { readonly findings: readonly LintFinding[] } | { readonly error: string };

/** Judges the mirrored Kotlin files (repo-relative, under [dir]). */
export interface StagedLint {
  (dir: string, files: readonly string[]): Promise<LintVerdict>;
}

interface Pinned {
  readonly url: (version: string) => string;
  readonly digests: Readonly<Record<string, string>>;
}

/** ktlint publishes one self-running file per release; detekt publishes its all-in-one jar to Maven Central. */
const PINNED: Readonly<Record<"ktlint" | "detekt", Pinned>> = {
  ktlint: {
    url: (v) => `https://github.com/ktlint/ktlint/releases/download/${v}/ktlint`,
    digests: { "1.8.0": "a3fd620207d5c40da6ca789b95e7f823c54e854b7fade7f613e91096a3706d75" },
  },
  detekt: {
    url: (v) => `https://repo1.maven.org/maven2/io/gitlab/arturbosch/detekt/detekt-cli/${v}/detekt-cli-${v}-all.jar`,
    digests: { "1.23.8": "3afe89a11120303c73c9bdda3d8fe558dd9070a6937d27819ddc04b275381245" },
  },
};

/** A source the module checks cover: `.kt` anywhere, `.kts` only under a `src/` directory (a root script is gradle's). */
export function lintable(path: string): boolean {
  return path.endsWith(".kt") || (path.endsWith(".kts") && path.split("/").includes("src"));
}

/** The version a catalog line names: `ktlint = "1.8.0"` under [versions]. */
export function catalogVersion(catalog: string, name: string): string | undefined {
  const m = new RegExp(`^${name}\\s*=\\s*"([^"]+)"`, "m").exec(catalog);
  return m?.[1];
}

function cacheDir(): string {
  return join(Bun.env.XDG_CACHE_HOME ?? join(homedir(), ".cache"), "splice-lint");
}

function sha256(bytes: Uint8Array): string {
  return createHash("sha256").update(bytes).digest("hex");
}

/** The pinned tool's file, fetched once and checked every time it is used. */
async function toolFile(tool: "ktlint" | "detekt", version: string): Promise<string> {
  const digest = PINNED[tool].digests[version];
  if (digest === undefined) {
    throw new Error(
      `the catalog names ${tool} ${version}, which has no sha256 in tools/gate/src/lib/lint-staged.ts: add the release's digest there`,
    );
  }
  const file = join(cacheDir(), `${tool}-${version}${tool === "detekt" ? ".jar" : ""}`);
  if (existsSync(file) && sha256(readFileSync(file)) === digest) return file;
  const url = PINNED[tool].url(version);
  const response = await fetch(url);
  if (!response.ok) throw new Error(`cannot fetch ${tool} ${version} from ${url}: HTTP ${response.status}`);
  const bytes = new Uint8Array(await response.arrayBuffer());
  if (sha256(bytes) !== digest) throw new Error(`${url} does not match the sha256 pinned for ${tool} ${version}`);
  mkdirSync(cacheDir(), { recursive: true });
  const staged = `${file}.${process.pid}.tmp`;
  writeFileSync(staged, bytes);
  chmodSync(staged, 0o755);
  renameSync(staged, file);
  return file;
}

interface Ran {
  readonly status: number;
  readonly output: string;
}

function run(command: string, args: readonly string[], cwd: string, env: Record<string, string>): Promise<Ran> {
  return new Promise((resolve, reject) => {
    const child = spawn(command, [...args], { cwd, env, stdio: ["ignore", "pipe", "pipe"] });
    let output = "";
    child.stdout.on("data", (chunk) => (output += String(chunk)));
    child.stderr.on("data", (chunk) => (output += String(chunk)));
    child.on("error", reject);
    child.on("close", (status) => resolve({ status: status ?? -1, output }));
  });
}

/** The lines a linter printed that name a finding, with the mirror's directory taken off the path. */
export function findingLines(tool: "ktlint" | "detekt", output: string, dir: string): LintFinding[] {
  const prefix = `${dir}/`;
  return output
    .split("\n")
    .map((line) => line.trim())
    .filter((line) => line.startsWith(prefix) && /:\d+:\d+: /.test(line))
    .map((line) => ({ tool, line: line.slice(prefix.length) }));
}

/** The real linters, at the versions [root]'s catalog names. */
export function stagedLinters(root: string): StagedLint {
  return async (dir, files) => {
    try {
      const catalog = readFileSync(join(root, "gradle", "libs.versions.toml"), "utf8");
      const ktlintVersion = catalogVersion(catalog, "ktlint");
      const detektVersion = catalogVersion(catalog, "detekt");
      if (ktlintVersion === undefined || detektVersion === undefined) {
        return { error: "gradle/libs.versions.toml names no ktlint or no detekt version" };
      }
      const jdk = resolveJdk21();
      if ("error" in jdk) return { error: jdk.error };
      const java = join(jdk.javaHome, "bin", "java");
      const [ktlint, detekt] = await Promise.all([toolFile("ktlint", ktlintVersion), toolFile("detekt", detektVersion)]);
      copyFileSync(join(root, ".editorconfig"), join(dir, ".editorconfig"));
      const env = { ...(Bun.env as Record<string, string>), JAVA_HOME: jdk.javaHome };
      const sources = files.filter((file) => file.endsWith(".kt"));
      const [formatted, analysed] = await Promise.all([
        run(java, ["-jar", ktlint, ...files], dir, env),
        sources.length === 0
          ? Promise.resolve({ status: 0, output: "" })
          : run(
              java,
              [
                "-jar",
                detekt,
                "--input",
                sources.map((file) => join(dir, file)).join(","),
                "--config",
                join(root, "quality", "detekt", "detekt.yml"),
                "--build-upon-default-config",
              ],
              dir,
              env,
            ),
      ]);
      // ktlint exits 1 on findings; detekt exits 2 on findings. Anything else is a linter that did not judge.
      if (formatted.status !== 0 && formatted.status !== 1) return { error: `ktlint exited ${formatted.status}: ${formatted.output.trim()}` };
      if (analysed.status !== 0 && analysed.status !== 2) return { error: `detekt exited ${analysed.status}: ${analysed.output.trim()}` };
      return {
        findings: [...findingLines("ktlint", formatted.output, dir), ...findingLines("detekt", analysed.output, dir)],
      };
    } catch (error) {
      return { error: error instanceof Error ? error.message : String(error) };
    }
  };
}
