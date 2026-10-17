// The JDK-21 resolver, proven on fake launchers so the three branches gate.sh:14-50 had are each
// exercised without depending on what this machine has installed.
import { describe, expect, test } from "bun:test";
import { chmodSync, mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { run } from "../src/commands/run.ts";
import { javaMajor, majorFrom, resolveJdk21 } from "../src/lib/jdk.ts";

/** A `java` launcher that reports `version` and, under -XshowSettings, a java.home of `home`. */
function fakeJava(dir: string, version: string, home: string): string {
  mkdirSync(join(dir, "bin"), { recursive: true });
  const bin = join(dir, "bin", "java");
  writeFileSync(
    bin,
    `#!/bin/sh\n` +
      `case "$*" in *XshowSettings*) printf '    java.home = ${home}\\n' >&2 ;; esac\n` +
      `printf 'openjdk version "${version}" 2024-07-16\\n' >&2\n`,
  );
  chmodSync(bin, 0o755);
  return bin;
}

describe("the JDK 21 resolver", () => {
  test("reads the major the way gate.sh's awk/cut pipeline did", () => {
    expect(majorFrom('openjdk version "21.0.4" 2024-07-16\nOpenJDK Runtime Environment')).toBe("21");
    expect(majorFrom('java version "1.8.0_392"')).toBe("1");
    expect(majorFrom("no version line at all")).toBe("");
  });

  test("a caller-provided JAVA_HOME that is a 21 wins, untouched", () => {
    const box = mkdtempSync(join(tmpdir(), "gate-jdk-"));
    try {
      fakeJava(join(box, "declared"), "21.0.4", "/should/not/be/read");
      const jdk = resolveJdk21({ JAVA_HOME: join(box, "declared"), PATH: "/nonexistent" });
      expect(jdk).toEqual({ javaHome: join(box, "declared") });
    } finally {
      rmSync(box, { recursive: true, force: true });
    }
  });

  test("otherwise the java on PATH decides, and its home comes from java.home", () => {
    const box = mkdtempSync(join(tmpdir(), "gate-jdk-"));
    try {
      fakeJava(join(box, "declared"), "17.0.2", "/seventeen");
      fakeJava(join(box, "onpath"), "21.0.4", join(box, "reported-home"));
      const jdk = resolveJdk21({ JAVA_HOME: join(box, "declared"), PATH: join(box, "onpath", "bin") });
      expect(jdk).toEqual({ javaHome: join(box, "reported-home") });
      // and with JAVA_HOME unset entirely — the OSS-J shape
      expect(resolveJdk21({ PATH: join(box, "onpath", "bin") })).toEqual({ javaHome: join(box, "reported-home") });
    } finally {
      rmSync(box, { recursive: true, force: true });
    }
  });

  test("no 21 anywhere is a refusal that names both places it looked and the remedy", () => {
    const box = mkdtempSync(join(tmpdir(), "gate-jdk-"));
    try {
      fakeJava(join(box, "onpath"), "17.0.2", "/seventeen");
      const jdk = resolveJdk21({ PATH: join(box, "onpath", "bin") });
      expect("error" in jdk).toBe(true);
      const message = (jdk as { error: string }).error;
      expect(message).toContain("JDK 21 required");
      expect(message).toContain("$JAVA_HOME and PATH java");
      expect(message).toContain("adoptium.net");
    } finally {
      rmSync(box, { recursive: true, force: true });
    }
  });
});

describe("`gate run --java-home-only`", () => {
  test("drives the verb directly and prints a JAVA_HOME that is a real JDK 21", async () => {
    const originalLog = console.log;
    const lines: string[] = [];
    console.log = (line: string): void => {
      lines.push(line);
    };
    let exitCode: number;
    try {
      exitCode = await run(["--java-home-only"]);
    } finally {
      console.log = originalLog;
    }
    expect(exitCode).toBe(0);
    expect(lines).toHaveLength(1);
    const javaHome = lines[0]!;
    expect(javaMajor(join(javaHome, "bin", "java"))).toBe("21");
  });
});
