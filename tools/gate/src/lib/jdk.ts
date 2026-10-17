// Which JDK the gate runs on — resolved from the running VM's own `java.home` property, never from an
// OS- or package-manager-specific path. Splice requires JDK 21 (module law + toolchain).
//
// A port of checks/gate.sh:14-50, which owned this until PR 5. The two branches are the script's:
//   1. a caller-provided JAVA_HOME whose bin/java reports major 21 wins, untouched;
//   2. otherwise the `java` on PATH, if it reports 21, and its home is read from
//      `-XshowSettings:properties` — which works for Linux and macOS launchers, /usr/bin/java
//      included, without non-portable readlink flags.
// Anything else is a refusal with the install remedies attached (OSS-J pins that the resolver
// works with JAVA_HOME unset and returns a Java 21).
import { accessSync, constants } from "node:fs";
import { join } from "node:path";

export type Jdk = { readonly javaHome: string } | { readonly error: string };

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

export function resolveJdk21(env: Record<string, string | undefined> = Bun.env): Jdk {
  const declared = env.JAVA_HOME;
  if (declared) {
    const bin = join(declared, "bin", "java");
    if (executable(bin) && javaMajor(bin, env) === "21") return { javaHome: declared };
  }
  const onPath = Bun.which("java", { PATH: env.PATH ?? "" });
  if (onPath && javaMajor(onPath, env) === "21") {
    const home = javaHomeOf(onPath, env);
    if (home) return { javaHome: home };
  }
  return {
    error:
      "GATE: FAIL — JDK 21 required, none found (checked $JAVA_HOME and PATH java).\n" +
      "  Install a JDK 21 and re-run, e.g.:\n" +
      "    macOS:  brew install openjdk@21\n" +
      "    Debian/Ubuntu: sudo apt install openjdk-21-jdk\n" +
      "    or download from https://adoptium.net/temurin/releases/?version=21",
  };
}

function stringEnv(env: Record<string, string | undefined>): Record<string, string> {
  const out: Record<string, string> = {};
  for (const [key, value] of Object.entries(env)) if (typeof value === "string") out[key] = value;
  return out;
}
