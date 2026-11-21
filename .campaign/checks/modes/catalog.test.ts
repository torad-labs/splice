// modes-catalog: a mode's own tests, as the template ships them and as they travel with a copy, load the mode the way
// splice's catalog does and fail on every finding at once, each naming the file or key at fault. A copied mode that
// is whole passes; one missing a tool's file, with a schema that is not an object, a name that is not its folder's, a
// handler that is not the class found, a tool it lists without files, an unknown kind, or resources on a door mode
// fails. An `mcp` mode with resources and a tool's _meta is accepted.

import { afterAll, beforeAll, describe, expect, test } from "bun:test";
import { Tree, requireTemplate } from "./lib/modes.ts";

const LONG = 3_600_000;

let tree: Tree;

beforeAll(() => {
  requireTemplate();
  tree = Tree.make();
  tree.copyMode("template", "echo");
  tree.copyMode("template", "visual");
  tree.edit("modes/visual/mode.toml", (t) => t.replace(/^kind\s*=.*$/m, 'kind = "mcp"'));
  tree.edit(
    "modes/visual/mode.toml",
    (t) =>
      `${t}\n[[resources]]\nuri = "ui://visual/app.html"\nname = "app"\nmimeType = "text/html;profile=mcp-app"\nfile = "app.html"\n_meta = { ui = { csp = { connectDomains = [] } } }\n`,
  );
  tree.write("modes/visual/resources/app.html", "<!doctype html><title>app</title><p>hello</p>\n");
  tree.write("modes/visual/tools/say_back.meta.json", `${JSON.stringify({ ui: { resourceUri: "ui://visual/app.html" } })}\n`);
  tree.commit("echo and visual, whole");
}, LONG);

afterAll(() => tree?.dispose());

/** `:modes-<name>:test`: its exit code and what its reports say. */
function tested(name: string) {
  const r = tree.gradle([`:modes-${name}:test`]);
  return { code: r.code, out: `${r.out}\n${r.err}`, report: tree.testReport(`modes/${name}`) };
}

/** Breaks modes/echo with `breakIt`, runs its tests, and gives back what they said; the folder is whole again after. */
function broken(breakIt: () => void): string {
  breakIt();
  try {
    const t = tested("echo");
    expect(t.code, "a broken mode's own tests fail").not.toBe(0);
    expect(t.report.failures, t.out.slice(-4000)).toBeGreaterThan(0);
    return t.report.said;
  } finally {
    tree.restore();
  }
}

describe("modes-catalog", () => {
  test("a whole copy, door or mcp, passes its own tests", () => {
    for (const name of ["echo", "visual"]) {
      const t = tested(name);
      expect(t.code, t.out.slice(-6000)).toBe(0);
      expect(t.report.tests, `${name}'s tests ran`).toBeGreaterThan(0);
      expect(t.report.failures, t.report.said).toBe(0);
    }
  }, LONG);

  test("a tool without its description file is a finding that names the file", () => {
    expect(broken(() => tree.remove("modes/echo/tools/say_back.md"))).toContain("say_back.md");
  }, LONG);

  test("a schema that is not a JSON Schema object is a finding that names the file", () => {
    expect(broken(() => tree.write("modes/echo/tools/say_back.json", '["not", "an", "object"]\n'))).toContain("say_back.json");
  }, LONG);

  test("a name that is not the folder's is a finding that names the key", () => {
    const said = broken(() => tree.edit("modes/echo/mode.toml", (t) => t.replace(/^name\s*=.*$/m, 'name = "other"')));
    expect(said).toMatch(/\bname\b/);
    expect(said).toContain("echo");
  }, LONG);

  test("an unknown kind is a finding that names the key", () => {
    expect(broken(() => tree.edit("modes/echo/mode.toml", (t) => t.replace(/^kind\s*=.*$/m, 'kind = "sideways"')))).toMatch(/\bkind\b/);
  }, LONG);

  test("resources on a door mode are a finding", () => {
    const said = broken(() => tree.edit("modes/echo/mode.toml", (t) => `${t}\n[[resources]]\nuri = "ui://echo/app.html"\nmimeType = "text/html;profile=mcp-app"\n`));
    expect(said).toMatch(/\bresources\b/);
  }, LONG);

  test("every finding is listed at once: a missing file, a listed tool with no files and a handler that is not the class found", () => {
    const said = broken(() => {
      tree.remove("modes/echo/tools/say_back.md");
      tree.edit("modes/echo/mode.toml", (t) => t.replace(/^tools\s*=.*$/m, 'tools = ["say_back", "ghost"]').replace(/^handler\s*=.*$/m, 'handler = "splice.modes.echo.Missing"'));
    });
    expect(said).toContain("say_back.md");
    expect(said).toContain("ghost");
    expect(said).toContain("splice.modes.echo.Missing");
  }, LONG);
});
