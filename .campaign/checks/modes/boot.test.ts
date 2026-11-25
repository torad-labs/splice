// modes-boot: splice checks every mode it carries and every `[modes.<name>]` table at boot, and fails closed. A mode
// whose folder is broken (a tool without its description file, a server another mode has, a schema that is not an
// object), a table naming no mode, a key the table does not take, a value of the wrong type and a head splice.toml
// does not declare are each a finding; every one is listed at once, the daemon does not start, and `splice
// check-config` refuses the same file with the same findings. No finding quotes a value. A mode of kind "mcp" and a
// well-formed table boot.

import { afterAll, beforeAll, describe, expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import { Splice, freePort, mockHead } from "../server/lib/harness.ts";
import { Upstream } from "../server/lib/upstream.ts";
import { Tree, modesTable, requireTemplate } from "./lib/modes.ts";

const LONG = 3_600_000;

let up: Upstream;

beforeAll(() => {
  requireTemplate();
  up = new Upstream("astra", "astra-large").start();
});

afterAll(() => up?.stop());

const astra = async () => mockHead({ key: "astra", command: "claude-astra", port: await freePort(), url: up.url, model: "astra-large" });

/** The daemon log's lines since `from`. */
const linesOf = (log: string, from = 0) => readFileSync(log, "utf8").slice(from).split("\n");

describe("modes-boot: splice.toml", () => {
  test("a well-formed [modes.template] boots", async () => {
    const s = await Splice.boot((await astra()) + modesTable("template", "on = true", "heads = { astra = true }"));
    await s.dispose();
  }, 300_000);

  test("a table naming no mode, an unknown key, a wrong type and an undeclared head refuse boot, all at once, quoting no value", async () => {
    const s = await Splice.create(
      (await astra()) + modesTable("ghost", "on = true") + modesTable("template", 'on = "certainly"', 'colour = "vermilion"', "heads = { nohead = true }"),
    );
    try {
      await expect(s.start()).rejects.toThrow();
      const lines = linesOf(s.log);
      const finding = (says: RegExp) => lines.some((l) => says.test(l));
      expect(finding(/modes\.ghost/), "the table naming no mode").toBe(true);
      expect(finding(/modes\.template\.colour/), "the key the table does not take").toBe(true);
      expect(finding(/modes\.template\.on\b/), "on that is not true or false").toBe(true);
      expect(finding(/nohead/), "the head splice.toml does not declare").toBe(true);
      expect(lines.some((l) => l.includes("certainly") || l.includes("vermilion")), "no finding quotes a value").toBe(false);

      const r = s.cli(["check-config"]);
      expect(r.code).toBe(3);
      const said = `${r.out}${r.err}`;
      for (const word of ["modes.ghost", "modes.template.colour", "modes.template.on", "nohead"]) expect(said).toContain(word);
    } finally {
      await s.dispose();
    }
  }, 300_000);
});

describe("modes-boot: the modes splice carries", () => {
  let tree: Tree;

  beforeAll(() => {
    tree = Tree.make();
    tree.copyMode("template", "echo");
    tree.edit("modes/echo/mode.toml", (t) => t.replace(/^on\s*=.*$/m, "on = true"));
    tree.copyMode("template", "sketch");
    tree.edit("modes/sketch/mode.toml", (t) => t.replace(/^kind\s*=.*$/m, 'kind = "mcp"'));
    tree.edit("modes/sketch/mode.toml", (t) => `${t}\n[[resources]]\nuri = "ui://sketch/app.html"\nmimeType = "text/html;profile=mcp-app"\nfile = "app.html"\n`);
    tree.write("modes/sketch/resources/app.html", "<!doctype html><title>app</title>\n");
    tree.commit("echo and sketch");
  }, LONG);

  afterAll(() => tree?.dispose());

  test("a door mode and an mcp mode that are whole boot", async () => {
    tree.jar();
    const H = await tree.harness();
    const s = await H.Splice.boot(await astra());
    await s.dispose();
  }, LONG);

  test("broken modes refuse boot, each named with what is wrong, all at once, and check-config says the same", async () => {
    tree.copyMode("template", "nofiles");
    tree.remove("modes/nofiles/tools/say_back.md");
    tree.copyMode("template", "twin");
    tree.edit("modes/twin/mode.toml", (t) => t.replace(/^server\s*=.*$/m, 'server = "echo"'));
    tree.copyMode("template", "badschema");
    tree.write("modes/badschema/tools/say_back.json", '"a string, not an object"\n');
    try {
      tree.jar();
      const H = await tree.harness();
      const s = await H.Splice.create(await astra());
      try {
        await expect(s.start()).rejects.toThrow();
        const lines = linesOf(s.log);
        const finding = (...words: string[]) => lines.some((l) => words.every((w) => l.includes(w)));
        expect(finding("nofiles", "say_back.md"), "the tool without its description file").toBe(true);
        expect(finding("twin", "server"), "the server two modes share").toBe(true);
        expect(finding("badschema", "say_back.json"), "the schema that is not an object").toBe(true);
        const r = s.cli(["check-config"]);
        expect(r.code).toBe(3);
        for (const name of ["nofiles", "twin", "badschema"]) expect(`${r.out}${r.err}`).toContain(name);
      } finally {
        await s.dispose();
      }
    } finally {
      tree.restore();
    }
  }, LONG);
});
