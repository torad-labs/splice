// The JDK-21 resolver, proven on fake launchers so each branch is exercised without depending on what this machine has
// installed: a caller's JAVA_HOME, the java on PATH, and the folders installers put JDKs in, which Gradle's own toolchain
// detection reads too (the branch 2026-11-02 added, after a machine whose default java moved to 25 with three JDK 21s
// installed had every commit with Kotlin and every push refused).
import { describe, expect, test } from "bun:test";
import { chmodSync, mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { run } from "../src/commands/run.ts";
import { javaMajor, jdkRoots, majorFrom, resolveJdk21 } from "../src/lib/jdk.ts";

/** A JDK: a `java` launcher that reports `version` and, under -XshowSettings, a java.home of `home`, beside a `javac`. */
function fakeJava(dir: string, version: string, home: string): string {
  const bin = fakeJre(dir, version, home);
  writeFileSync(join(dir, "bin", "javac"), "#!/bin/sh\nexit 0\n");
  chmodSync(join(dir, "bin", "javac"), 0o755);
  return bin;
}

/** A JRE: the same launcher with no `javac` beside it, as Debian's openjdk-21-jre-headless lays it down. */
function fakeJre(dir: string, version: string, home: string): string {
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
      const jdk = resolveJdk21({ JAVA_HOME: join(box, "declared"), PATH: "/nonexistent" }, []);
      expect(jdk).toEqual({ javaHome: join(box, "declared"), source: "JAVA_HOME" });
    } finally {
      rmSync(box, { recursive: true, force: true });
    }
  });

  test("otherwise the java on PATH decides, and its home comes from java.home", () => {
    const box = mkdtempSync(join(tmpdir(), "gate-jdk-"));
    try {
      fakeJava(join(box, "declared"), "17.0.2", "/seventeen");
      // the launcher on PATH is a stub (as /usr/bin/java is) whose java.home is the JDK itself
      fakeJava(join(box, "onpath"), "21.0.4", join(box, "reported-home"));
      fakeJava(join(box, "reported-home"), "21.0.4", join(box, "reported-home"));
      const jdk = resolveJdk21({ JAVA_HOME: join(box, "declared"), PATH: join(box, "onpath", "bin") }, []);
      expect(jdk).toEqual({ javaHome: join(box, "reported-home"), source: "PATH java" });
      // and with JAVA_HOME unset entirely — the OSS-J shape
      expect(resolveJdk21({ PATH: join(box, "onpath", "bin") }, [])).toEqual({ javaHome: join(box, "reported-home"), source: "PATH java" });
    } finally {
      rmSync(box, { recursive: true, force: true });
    }
  });

  test("a JDK 21 installed under a known folder is found when the default java is newer, and the folder is named", () => {
    const box = mkdtempSync(join(tmpdir(), "gate-jdk-"));
    try {
      fakeJava(join(box, "onpath"), "25.0.3", "/twenty-five");
      fakeJava(join(box, "jvm", "java-25-openjdk-amd64"), "25.0.3", "/twenty-five");
      fakeJava(join(box, "jvm", "java-21-openjdk-amd64"), "21.0.11", "/should/not/be/read");
      fakeJava(join(box, "jdks", "jdk-21.0.11"), "21.0.11", "/should/not/be/read");
      // user folders before system ones, first 21 wins; a 25 in the same folder is passed over
      const jdk = resolveJdk21({ PATH: join(box, "onpath", "bin") }, [join(box, "jdks"), join(box, "jvm")]);
      expect(jdk).toEqual({ javaHome: join(box, "jdks", "jdk-21.0.11"), source: `installed under ${join(box, "jdks")}` });
      expect(resolveJdk21({ PATH: join(box, "onpath", "bin") }, [join(box, "jvm")])).toEqual({
        javaHome: join(box, "jvm", "java-21-openjdk-amd64"),
        source: `installed under ${join(box, "jvm")}`,
      });
      // a macOS bundle keeps its home under Contents/Home
      fakeJava(join(box, "Machines", "temurin-21.jdk", "Contents", "Home"), "21.0.11", "/should/not/be/read");
      expect(resolveJdk21({ PATH: join(box, "onpath", "bin") }, [join(box, "Machines")])).toEqual({
        javaHome: join(box, "Machines", "temurin-21.jdk", "Contents", "Home"),
        source: `installed under ${join(box, "Machines")}`,
      });
    } finally {
      rmSync(box, { recursive: true, force: true });
    }
  });

  test("the folders come from the home: Gradle's org.gradle.java.installations.paths first, then the installers' folders", () => {
    const box = mkdtempSync(join(tmpdir(), "gate-jdk-"));
    try {
      mkdirSync(join(box, "home", ".gradle"), { recursive: true });
      writeFileSync(join(box, "home", ".gradle", "gradle.properties"), `org.gradle.jvmargs=-Xmx2g\norg.gradle.java.installations.paths=${join(box, "pinned")},${join(box, "also")}\n`);
      const roots = jdkRoots({ HOME: join(box, "home") });
      expect(roots.slice(0, 2)).toEqual([join(box, "pinned"), join(box, "also")]);
      expect(roots).toContain(join(box, "home", "jdks"));
      expect(roots).toContain(join(box, "home", ".jdks"));
      expect(roots).toContain(join(box, "home", ".gradle", "jdks"));
      expect(roots).toContain(join(box, "home", ".sdkman", "candidates", "java"));
      expect(roots).toContain(join(box, "home", ".asdf", "installs", "java"));
      // IntelliJ's macOS download folder, which `java_home -V` lists and Gradle's detection reads
      expect(roots).toContain(join(box, "home", "Library", "Java", "JavaVirtualMachines"));
      // the Linux folders Gradle's installation supplier scans: distro packages, Oracle RPMs, hand installs
      for (const system of ["/usr/lib/jvm", "/usr/lib64/jvm", "/usr/java", "/usr/local/java", "/opt/java", "/Library/Java/JavaVirtualMachines"]) {
        expect(roots).toContain(system);
      }
      // the user's folders come before the system's
      expect(roots.indexOf(join(box, "home", "Library", "Java", "JavaVirtualMachines"))).toBeLessThan(roots.indexOf("/usr/lib/jvm"));
      // a pinned path that IS a JDK (not a folder of them) resolves as itself
      fakeJava(join(box, "pinned"), "21.0.11", "/should/not/be/read");
      fakeJava(join(box, "onpath"), "25.0.3", "/twenty-five");
      expect(resolveJdk21({ HOME: join(box, "home"), PATH: join(box, "onpath", "bin") })).toEqual({
        javaHome: join(box, "pinned"),
        source: `installed under ${join(box, "pinned")}`,
      });
    } finally {
      rmSync(box, { recursive: true, force: true });
    }
  });

  test("a JRE 21, a java with no javac beside it, is not a JDK: it is passed over wherever it sits, and the refusal names it", () => {
    const box = mkdtempSync(join(tmpdir(), "gate-jdk-"));
    try {
      // Debian's jre-headless sorts before a Temurin JDK in the same folder; the JDK is still the answer
      fakeJre(join(box, "jvm", "java-21-openjdk-amd64"), "21.0.11", "/should/not/be/read");
      fakeJava(join(box, "jvm", "temurin-21-jdk-amd64"), "21.0.11", "/should/not/be/read");
      fakeJava(join(box, "onpath"), "25.0.3", "/twenty-five");
      expect(resolveJdk21({ PATH: join(box, "onpath", "bin") }, [join(box, "jvm")])).toEqual({
        javaHome: join(box, "jvm", "temurin-21-jdk-amd64"),
        source: `installed under ${join(box, "jvm")}`,
      });
      // the java on PATH is a JRE 21: its home has no javac, so the search goes on to the folders
      fakeJre(join(box, "jre-on-path"), "21.0.11", join(box, "jre-home"));
      fakeJre(join(box, "jre-home"), "21.0.11", join(box, "jre-home"));
      expect(resolveJdk21({ PATH: join(box, "jre-on-path", "bin") }, [join(box, "jvm")])).toEqual({
        javaHome: join(box, "jvm", "temurin-21-jdk-amd64"),
        source: `installed under ${join(box, "jvm")}`,
      });
      // a machine whose only 21 is a JRE is refused, and told which folder holds a JRE and not a JDK
      const jdk = resolveJdk21({ PATH: join(box, "onpath", "bin") }, [join(box, "jre-home")]);
      expect("error" in jdk).toBe(true);
      const message = (jdk as { error: string }).error;
      expect(message).toContain("JDK 21 required");
      expect(message).toContain(`${join(box, "jre-home")} is a JRE`);
    } finally {
      rmSync(box, { recursive: true, force: true });
    }
  });

  test("the Gradle pin is read as java.util.Properties reads it: continuation lines, `:` and whitespace separators, the last assignment", () => {
    const box = mkdtempSync(join(tmpdir(), "gate-jdk-"));
    try {
      const pinned = (text: string): string[] => {
        const gradleHome = mkdtempSync(join(box, "gradle-"));
        writeFileSync(join(gradleHome, "gradle.properties"), text);
        return jdkRoots({ HOME: join(box, "home"), GRADLE_USER_HOME: gradleHome }).slice(0, 2);
      };
      expect(pinned(`org.gradle.java.installations.paths=${join(box, "a")},\\\n    ${join(box, "b")}\n`)).toEqual([join(box, "a"), join(box, "b")]);
      expect(pinned(`org.gradle.java.installations.paths: ${join(box, "a")}, ${join(box, "b")}\n`)).toEqual([join(box, "a"), join(box, "b")]);
      expect(pinned(`org.gradle.java.installations.paths ${join(box, "a")},${join(box, "b")}\n`)).toEqual([join(box, "a"), join(box, "b")]);
      expect(pinned(`org.gradle.java.installations.paths=${join(box, "old")}\n# moved:\norg.gradle.java.installations.paths=${join(box, "a")},${join(box, "b")}\n`)).toEqual([join(box, "a"), join(box, "b")]);
      expect(pinned(`# org.gradle.java.installations.paths=${join(box, "commented")}\norg.gradle.jvmargs=-Xmx2g\n`)[0]).not.toBe(join(box, "commented"));
    } finally {
      rmSync(box, { recursive: true, force: true });
    }
  });

  test("the folders honor the variables Gradle honors: GRADLE_USER_HOME, SDKMAN_CANDIDATES_DIR and ASDF_DATA_DIR", () => {
    const box = mkdtempSync(join(tmpdir(), "gate-jdk-"));
    try {
      const gradleHome = join(box, "gradle-user-home");
      mkdirSync(gradleHome, { recursive: true });
      writeFileSync(join(gradleHome, "gradle.properties"), `org.gradle.java.installations.paths=${join(box, "pinned")}\n`);
      const roots = jdkRoots({
        HOME: join(box, "home"),
        GRADLE_USER_HOME: gradleHome,
        SDKMAN_CANDIDATES_DIR: join(box, "sdkman-candidates"),
        ASDF_DATA_DIR: join(box, "asdf-data"),
      });
      // the pin in the moved gradle home is read, and its jdks folder is searched
      expect(roots[0]).toBe(join(box, "pinned"));
      expect(roots).toContain(join(gradleHome, "jdks"));
      expect(roots).toContain(join(box, "sdkman-candidates", "java"));
      expect(roots).toContain(join(box, "asdf-data", "installs", "java"));
      // the defaults under the home stay, so a machine with both is searched in both
      expect(roots).toContain(join(box, "home", ".gradle", "jdks"));
      expect(roots).toContain(join(box, "home", ".sdkman", "candidates", "java"));
    } finally {
      rmSync(box, { recursive: true, force: true });
    }
  });

  test("no 21 anywhere is a refusal that names every place it looked and the remedies", () => {
    const box = mkdtempSync(join(tmpdir(), "gate-jdk-"));
    try {
      fakeJava(join(box, "onpath"), "17.0.2", "/seventeen");
      fakeJava(join(box, "jvm", "java-25"), "25.0.3", "/twenty-five");
      const jdk = resolveJdk21({ PATH: join(box, "onpath", "bin") }, [join(box, "jvm"), join(box, "absent")]);
      expect("error" in jdk).toBe(true);
      const message = (jdk as { error: string }).error;
      expect(message).toContain("JDK 21 required");
      expect(message).toContain("$JAVA_HOME, $SPLICE_JAVA, PATH java");
      expect(message).toContain(join(box, "jvm"));
      expect(message).toContain(join(box, "absent"));
      expect(message).toContain("org.gradle.java.installations.paths");
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
