// share-door-only, docs-door-only: what splice says about sharing, in every place a person or an agent reads it, is
// the door alone: one `splice remote add http://<box>`, and no address per command, `?auth=` or `?models=`.
// lan-share.md says at its top that the door replaced it; door-network.md no longer says nothing is removed; the
// changelog's Unreleased section says what went and what a leftover address does now.

import { describe, expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { ROOT } from "../server/lib/harness.ts";

const read = (path: string) => readFileSync(join(ROOT, path), "utf8");

/** What a person or an agent reads about sharing: none of it may offer an address per command. */
const READ = [
  "README.md",
  "app/src/main/resources/manager-home/.claude/skills/splice/reference/cli.md",
  "app/src/main/resources/manager-home/.claude/skills/splice/reference/toml-reference.md",
  "app/src/main/resources/splice.example.toml",
  "tools/release/downloads/website/bands/share.html",
];

describe("docs-door-only", () => {
  test("no page a person or an agent reads offers an address per command, ?auth= or ?models=", () => {
    const found = READ.flatMap((path) => {
      const text = read(path);
      return [
        ...(text.includes("?auth=") ? [`${path}: ?auth=`] : []),
        ...(text.includes("?models=") ? [`${path}: ?models=`] : []),
        ...(/3097\/(?!door\b)[a-z]/.test(text) ? [`${path}: an address per command on the share port`] : []),
        ...(/\{\{share-port\}\}\/(?!door\b)/.test(text) ? [`${path}: an address per command on the share port`] : []),
      ];
    });
    expect(found).toEqual([]);
  });

  test("the pages that teach sharing teach the door's one add", () => {
    for (const path of ["README.md", "app/src/main/resources/manager-home/.claude/skills/splice/reference/cli.md"]) {
      expect([path, /splice remote add http:\/\/[\w.<>-]+(:\d+)?(?=[\s`'"]|$)/m.test(read(path))]).toEqual([path, true]);
    }
  });

  test("lan-share.md says at its top that the door replaced it", () => {
    const top = read("docs/specs/lan-share.md").split("\n").slice(0, 16).join("\n");
    expect(top).toContain("door-network.md");
    expect(top).toMatch(/supersed|replaced/i);
  });

  test("door-network.md no longer says one command's address still works or an older box is added per command", () => {
    const doc = read("docs/specs/door-network.md");
    expect(doc).not.toMatch(/Nothing is removed/);
    expect(doc).not.toMatch(/added as before/);
    expect(doc).not.toMatch(/\/claudex`?\)? still works/);
  });

  test("the changelog's Unreleased section says the per-command share is gone and names the door's add", () => {
    const log = read("CHANGELOG.md");
    const unreleased = log.slice(0, log.indexOf("\n## splice v"));
    expect(unreleased).toContain("## Unreleased");
    expect(unreleased).toMatch(/splice remote add http:\/\//);
    expect(unreleased).toMatch(/door/);
  });
});
