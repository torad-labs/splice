// The DENOMINATOR, derived from the source rather than from the list being checked (constitution
// Article V.4). A rule's `files:` glob cannot supply its own expected set: a glob narrowed from
// `gateway/*/src/main/**` to `app/src/main/**` still matches everything it names, so a
// denominator read off the glob agrees with the mutation. These roots come from
// settings.gradle.kts and package.json instead, and the file lists come from `git ls-files`.
import { existsSync, readFileSync } from "node:fs";
import { dirname, join, relative } from "node:path";

export interface GradleModule {
  readonly id: string;
  /** repo-relative directory, e.g. `gateway/core` */
  readonly dir: string;
}

export interface SourceUnit {
  /** repo-relative directory, e.g. `core/src/main/kotlin` — also the report key */
  readonly key: string;
  readonly module: string;
  /** `main` | `test` | `testFixtures` for Gradle, `src` for a Bun workspace surface */
  readonly sourceSet: string;
  readonly kind: "gradle" | "workspace";
  readonly files: readonly string[];
}

export const GRADLE_SOURCE_SETS = ["main", "test", "testFixtures"] as const;

/** Extensions ast-grep associates with a language id, for the languages this repo routes. */
export const LANGUAGE_EXTENSIONS: Readonly<Record<string, readonly string[]>> = {
  kotlin: [".kt", ".kts"],
  typescript: [".ts", ".mts", ".cts"],
  tsx: [".tsx"],
  css: [".css"],
};

/**
 * Every file in the working tree that is not ignored — tracked AND untracked-but-not-ignored.
 *
 * Not `git ls-files` alone: ast-grep scans the WORKING TREE minus .gitignore, so a source file that
 * exists but has not been committed yet is enforced by the walls and must be in the denominator
 * too. Measured 2026-09-20: a freshly written, still-untracked `arch-tests/.../ProjectMap.kt` was
 * inside three rules' globs, which made the ast-grep cross-check disagree with a tracked-only set
 * for a reason that had nothing to do with glob semantics.
 */
export function workingTreeFiles(repoRoot: string): string[] {
  const proc = Bun.spawnSync(["git", "ls-files", "--cached", "--others", "--exclude-standard", "-z"], { cwd: repoRoot });
  if (proc.exitCode !== 0) throw new Error(`gate: git ls-files failed in ${repoRoot}: ${proc.stderr.toString()}`);
  return proc.stdout
    .toString()
    .split("\0")
    .filter((f) => f.length > 0 && existsSync(join(repoRoot, f)))
    .sort();
}

/**
 * Modules from `settings.gradle.kts`: every `include(":id")`, plus any
 * `project(":id").projectDir = file("...")` that relocates one. Today no module relocates and the
 * directory is `<buildRoot>/<id>`; the projectDir arm is here because the restructure introduces
 * grouped directories with explicit projectDir (plan P3).
 */
export function readGradleModules(repoRoot: string, buildRoot: string): GradleModule[] {
  const settings = join(buildRoot, "settings.gradle.kts");
  const text = readFileSync(settings, "utf8");
  const ids = [...text.matchAll(/(?:^|[(,\s])["']:([A-Za-z0-9_.:-]+)["']/gm)].map((m) => m[1]!);
  if (ids.length === 0) throw new Error(`gate: ${settings} declares no include() — the module list cannot be empty`);
  const overrides = new Map<string, string>();
  for (const m of text.matchAll(/project\(\s*["']:([A-Za-z0-9_.:-]+)["']\s*\)\s*\.projectDir\s*=\s*\w*\(?\s*["']([^"']+)["']/g)) {
    overrides.set(m[1]!, m[2]!);
  }
  const seen = new Set<string>();
  const modules: GradleModule[] = [];
  for (const id of ids) {
    if (seen.has(id)) continue;
    seen.add(id);
    const override = overrides.get(id);
    const abs = override ? join(buildRoot, override) : join(buildRoot, ...id.split(":"));
    if (!existsSync(join(abs, "build.gradle.kts"))) {
      throw new Error(`gate: module :${id} is included by ${settings} but has no build.gradle.kts at ${abs}`);
    }
    modules.push({ id, dir: relative(repoRoot, abs) });
  }
  return modules;
}

/**
 * Bun/npm workspace directories from the root package.json, for the non-JVM surfaces. A pattern is
 * EXPANDED rather than skipped: dropping `packages/*` because it carries a `*` would quietly shrink
 * the denominator, which is the one thing this whole file exists to prevent.
 */
export function readWorkspaces(repoRoot: string): string[] {
  const pkgPath = join(repoRoot, "package.json");
  if (!existsSync(pkgPath)) return [];
  const pkg = JSON.parse(readFileSync(pkgPath, "utf8")) as { workspaces?: string[] | { packages?: string[] } };
  const patterns = Array.isArray(pkg.workspaces) ? pkg.workspaces : (pkg.workspaces?.packages ?? []);
  return patterns.flatMap((pattern) => {
    if (!pattern.includes("*")) return [pattern];
    return [...new Bun.Glob(`${pattern}/package.json`).scanSync({ cwd: repoRoot })].map((p) => dirname(p)).sort();
  });
}

/**
 * Every source root that exists and holds at least one tracked file. A root with no files is not
 * a root: enrolling it would make every rule fail for a directory nobody writes code in.
 */
export function sourceUnits(repoRoot: string, buildRoot: string, tracked: readonly string[]): SourceUnit[] {
  const units: SourceUnit[] = [];
  const under = (dir: string) => tracked.filter((f) => f.startsWith(`${dir}/`));
  for (const module of readGradleModules(repoRoot, buildRoot)) {
    for (const sourceSet of GRADLE_SOURCE_SETS) {
      const key = `${module.dir}/src/${sourceSet}/kotlin`;
      const files = under(key);
      if (files.length > 0) units.push({ key, module: module.id, sourceSet, kind: "gradle", files });
    }
  }
  for (const workspace of readWorkspaces(repoRoot)) {
    const key = `${workspace}/src`;
    const files = under(key);
    if (files.length > 0) units.push({ key, module: workspace, sourceSet: "src", kind: "workspace", files });
  }
  return units;
}

export function unitFilesFor(unit: SourceUnit, language: string): string[] {
  const extensions = LANGUAGE_EXTENSIONS[language];
  if (!extensions) throw new Error(`gate: no extension list for ast-grep language "${language}"`);
  return unit.files.filter((f) => extensions.some((ext) => f.endsWith(ext)));
}
