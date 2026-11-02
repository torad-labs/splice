// Which JDK the gate runs on. Splice builds on JDK 21 (module law + the Gradle toolchain splice.kotlin-common pins), whatever
// `java` the machine defaults to: the toolchain compiles and tests on a 21 Gradle finds, and the gate, the hooks' linters and
// the release launcher must run on the same one. Resolved in this order, the first 21 wins:
//   1. $JAVA_HOME, when its bin/java reports 21, untouched;
//   2. $SPLICE_JAVA, the java the launcher runs splice on (a machine whose default java is older or newer for its own work);
//   3. the `java` on PATH, when it reports 21, its home read from `-XshowSettings:properties` (Linux and macOS launchers,
//      /usr/bin/java included, without non-portable readlink flags);
//   4. a JDK 21 installed where Gradle's own toolchain detection and the common installers put them (jdkRoots): the paths
//      the gradle user home's gradle.properties names in org.gradle.java.installations.paths, then ~/.jdks, ~/.gradle/jdks,
//      ~/Library/Java/JavaVirtualMachines, SDKMAN, asdf, the Linux system folders and /Library/Java/JavaVirtualMachines (a
//      bundle's Contents/Home), plus ~/jdks, which the gate searches and Gradle does not. No vendor's own install path is
//      spelled here (the release-readiness law's jdk-no-homebrew-path, from OSS-J): a keg-only Homebrew JDK is found through
//      PATH or through the /Library/Java link its install prints, a Temurin cask through /Library/Java directly.
// A JDK is a home with bin/javac beside bin/java: a JRE 21 (Debian's openjdk-21-jre-headless, often pulled in by another
// package) reports 21 and compiles nothing, and Gradle's toolchain would refuse it later with a less useful message.
// Anything else is a refusal naming every place looked, the JREs passed over, and the remedies.
//
// 1-3 are a port of checks/gate.sh:14-50, which owned this until PR 5. 4 is from 2026-11-02: a machine whose default java
// moved to 25, with three JDK 21s installed, had every commit with Kotlin and every push refused, because nothing looked past
// PATH. A JDK a machine has is found; only a machine with none is told to install one.
import { accessSync, constants, existsSync, readdirSync, readFileSync, statSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";

export type Jdk = { readonly javaHome: string; readonly source: string } | { readonly error: string };

/** The MAJOR version `bin -version` reports: the quoted version's first dot-separated component
 *  (`"21.0.4"` → 21, `"1.8.0_392"` → 1), exactly as gate.sh's awk/cut pipeline read it. */
export function javaMajor(bin: string, env: Record<string, string | undefined> = Bun.env): string {
  const proc = Bun.spawnSync([bin, "-version"], { env: stringEnv(env), stdout: "pipe", stderr: "pipe" });
  return majorFrom(proc.stderr.toString() + proc.stdout.toString());
}

export function majorFrom(versionOutput: string): string {
  for (const line of versionOutput.split("\n")) {
    if (!/ version "/.test(line)) continue;
    const quoted = line.split('"')[1] ?? "";
    return quoted.split(".")[0] ?? "";
  }
  return "";
}

/** `java.home` as the launcher on PATH reports it. */
export function javaHomeOf(bin: string, env: Record<string, string | undefined> = Bun.env): string {
  const proc = Bun.spawnSync([bin, "-XshowSettings:properties", "-version"], {
    env: stringEnv(env),
    stdout: "pipe",
    stderr: "pipe",
  });
  const text = proc.stderr.toString() + proc.stdout.toString();
  const match = /^\s*java\.home = (.*)$/m.exec(text);
  return match?.[1]?.trim() ?? "";
}

function executable(path: string): boolean {
  try {
    accessSync(path, constants.X_OK);
    return true;
  } catch {
    return false;
  }
}

function isDirectory(path: string): boolean {
  try {
    return statSync(path).isDirectory();
  } catch {
    return false;
  }
}

/** The paths `org.gradle.java.installations.paths` names in the gradle user home's gradle.properties: Gradle's own way of
 *  pointing a machine at specific JDK folders, read here so the gate and the build answer alike. Read as java.util.Properties
 *  reads the file, which is how Gradle reads it: a line that is not a comment and ends in an odd run of backslashes continues on
 *  the next line, whose leading blanks are dropped; a comment line (`#` or `!` first) is never continued, whatever it ends in;
 *  `=`, `:` or whitespace separates the key; and the last assignment wins. */
function gradleInstallationPaths(gradleUserHome: string): string[] {
  const file = join(gradleUserHome, "gradle.properties");
  if (!existsSync(file)) return [];
  const logical: string[] = [];
  let pending: string | undefined;
  for (const natural of readFileSync(file, "utf8").split(/\r?\n/)) {
    const line = natural.replace(/^[ \t\f]+/, "");
    if (pending === undefined && (line.startsWith("#") || line.startsWith("!"))) continue;
    const run = /\\+$/.exec(line)?.[0].length ?? 0;
    if (run % 2 === 1) {
      pending = (pending ?? "") + line.slice(0, -1);
      continue;
    }
    logical.push((pending ?? "") + line);
    pending = undefined;
  }
  if (pending !== undefined) logical.push(pending);
  let value = "";
  for (const line of logical) {
    const match = /^org\.gradle\.java\.installations\.paths(?:\s*[=:]\s*|\s+)(.*)$/.exec(line);
    if (match) value = match[1] ?? "";
  }
  return value
    .split(",")
    .map((p) => p.trim())
    .filter((p) => p.length > 0);
}

/** Where installed JDKs live on this machine, in the order they are searched: the user's own pins and folders first, the
 *  system's after. Each entry is a folder OF JDKs, or a JDK itself (a pinned path). The list is the one Gradle's own toolchain
 *  detection scans, so a 21 `./gradlew` compiles on is one the gate finds: the gradle user home's pins and `jdks` download folder
 *  (GRADLE_USER_HOME, or ~/.gradle), IntelliJ's download folders (~/.jdks on Linux, ~/Library/Java/JavaVirtualMachines on macOS),
 *  SDKMAN's and asdf's candidate folders (at the variables those tools export, and their defaults under the home), and the
 *  system folders: distro packages under /usr/lib/jvm and /usr/lib64/jvm, Oracle RPMs under /usr/java, hand installs under
 *  /usr/local/java and /opt/java, and macOS bundles under /Library/Java/JavaVirtualMachines. One folder is the gate's own:
 *  ~/jdks, where hand-unpacked JDKs often go. Gradle does not scan it, so a plain `./gradlew` sees a JDK kept there only through
 *  the org.gradle.java.installations.paths pin. */
export function jdkRoots(env: Record<string, string | undefined> = Bun.env): string[] {
  const home = env.HOME ?? homedir();
  const gradleUserHome = env.GRADLE_USER_HOME ?? join(home, ".gradle");
  const roots = [
    ...gradleInstallationPaths(gradleUserHome),
    join(home, "jdks"),
    join(home, ".jdks"),
    join(home, "Library", "Java", "JavaVirtualMachines"),
    join(gradleUserHome, "jdks"),
    join(home, ".gradle", "jdks"),
    join(env.SDKMAN_CANDIDATES_DIR ?? join(home, ".sdkman", "candidates"), "java"),
    join(home, ".sdkman", "candidates", "java"),
    join(env.ASDF_DATA_DIR ?? join(home, ".asdf"), "installs", "java"),
    join(home, ".asdf", "installs", "java"),
    "/usr/lib/jvm",
    "/usr/lib64/jvm",
    "/usr/java",
    "/usr/local/java",
    "/opt/java",
    "/Library/Java/JavaVirtualMachines",
  ];
  return [...new Set(roots)];
}

/** A home that runs java: bin/java is there. A JRE qualifies; see [isJdk]. */
const runsJava = (home: string): boolean => executable(join(home, "bin", "java"));

/** A JDK home: bin/javac beside bin/java. Gradle's toolchain needs the compiler, and a JRE 21 would pass every version check
 *  here and fail the build later. */
const isJdk = (home: string): boolean => runsJava(home) && executable(join(home, "bin", "javac"));

/** The homes a root offers that run java: itself when it does, else each child, a macOS bundle read at its Contents/Home. */
function homesUnder(root: string): string[] {
  if (!isDirectory(root)) return [];
  if (runsJava(root)) return [root];
  const homes: string[] = [];
  for (const name of readdirSync(root).sort()) {
    const child = join(root, name);
    if (runsJava(child)) homes.push(child);
    else if (runsJava(join(child, "Contents", "Home"))) homes.push(join(child, "Contents", "Home"));
  }
  return homes;
}

/** The first JDK 21 under [roots], searched in order, or undefined. A JRE 21 is passed over and, given [passedOver], named in it. */
export function installedJdk21(
  roots: readonly string[],
  env: Record<string, string | undefined> = Bun.env,
  passedOver?: string[],
): { javaHome: string; source: string } | undefined {
  for (const root of roots) {
    for (const home of homesUnder(root)) {
      if (javaMajor(join(home, "bin", "java"), env) !== "21") continue;
      if (isJdk(home)) return { javaHome: home, source: `installed under ${root}` };
      passedOver?.push(home);
    }
  }
  return undefined;
}

export function resolveJdk21(env: Record<string, string | undefined> = Bun.env, roots: readonly string[] = jdkRoots(env)): Jdk {
  const jres: string[] = [];
  const declared = env.JAVA_HOME;
  if (declared && runsJava(declared) && javaMajor(join(declared, "bin", "java"), env) === "21") {
    if (isJdk(declared)) return { javaHome: declared, source: "JAVA_HOME" };
    jres.push(declared);
  }
  // SPLICE_JAVA is the java the launcher runs splice on, for a machine whose default java is another version for its own work
  // (an Android toolchain on 17, a desktop on 25). The gate builds the same code, so it takes the same JDK.
  const named = env.SPLICE_JAVA;
  if (named && executable(named) && javaMajor(named, env) === "21") {
    const home = javaHomeOf(named, env);
    if (home && isJdk(home)) return { javaHome: home, source: "SPLICE_JAVA" };
    if (home) jres.push(home);
  }
  const onPath = Bun.which("java", { PATH: env.PATH ?? "" });
  if (onPath && javaMajor(onPath, env) === "21") {
    const home = javaHomeOf(onPath, env);
    if (home && isJdk(home)) return { javaHome: home, source: "PATH java" };
    if (home) jres.push(home);
  }
  const installed = installedJdk21(roots, env, jres);
  if (installed) return installed;
  const passedOver = [...new Set(jres)].map((home) => `    ${home} is a JRE (bin/java with no bin/javac), which Gradle's toolchain cannot compile with\n`).join("");
  return {
    error:
      "GATE: FAIL — JDK 21 required, none found (checked $JAVA_HOME, $SPLICE_JAVA, PATH java, and these JDK folders:\n" +
      roots.map((root) => `    ${root}`).join("\n") +
      "\n  ).\n" +
      passedOver +
      "  Install a JDK 21 in one of those folders and re-run, e.g.:\n" +
      "    macOS:  brew install --cask temurin@21\n" +
      "    Debian/Ubuntu: sudo apt install openjdk-21-jdk\n" +
      "    or download from https://adoptium.net/temurin/releases/?version=21\n" +
      "  or name its folder for Gradle and the gate alike, in ~/.gradle/gradle.properties:\n" +
      "    org.gradle.java.installations.paths=/path/to/jdk-21",
  };
}

function stringEnv(env: Record<string, string | undefined>): Record<string, string> {
  const out: Record<string, string> = {};
  for (const [key, value] of Object.entries(env)) if (typeof value === "string") out[key] = value;
  return out;
}
