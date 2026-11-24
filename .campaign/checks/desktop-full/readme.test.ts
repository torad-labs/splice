// df-readme: a person learns how to put Claude Desktop on splice from README.md, in both modes, and what splice writes
// for it; CHANGELOG's Unreleased section says app mode is new. The README describes what the built product does: the
// stale claim that `splice desktop on` writes into ~/.claude/settings.json (gone since c6e1f107a) is no longer in it.

import { describe, expect, test } from "bun:test";
import { readFileSync } from "node:fs";

const README = readFileSync("docs/wiki/claude-desktop.md", "utf8");
const DISK = readFileSync("docs/wiki/disk-and-privacy.md", "utf8");
const CHANGELOG = readFileSync("CHANGELOG.md", "utf8");

/** The text under `heading` up to the next heading of the same level, or "" when there is no such heading. */
function section(text: string, heading: string): string {
  const lines = text.split("\n");
  const start = lines.findIndex((l) => l.trim() === heading);
  if (start < 0) return "";
  const level = heading.match(/^#+/)?.[0] ?? "##";
  const rest = lines.slice(start + 1);
  const end = rest.findIndex((l) => l.startsWith(`${level} `));
  return rest.slice(0, end < 0 ? rest.length : end).join("\n");
}

/** README's table rows (lines starting with `|`) whose first cell names `needle`. */
function fileRows(needle: string): string[] {
  return DISK.split("\n").filter((l) => l.startsWith("|") && (l.split("|")[1] ?? "").includes(needle));
}

const DESKTOP = section(README, "## Claude Desktop on splice");

describe("df-readme", () => {
  test("README has one section on Claude Desktop, named for the whole app, in place of the Code tab's", () => {
    expect(DESKTOP.length).toBeGreaterThan(0);
    expect(README).not.toContain("## Claude Desktop's Code tab on splice");
  });

  test("the section explains both modes and the commands a person types", () => {
    expect(DESKTOP).toContain("splice desktop on --app");
    expect(DESKTOP).toContain("splice desktop off");
    expect(DESKTOP).toContain("standard mode");
    expect(DESKTOP).toContain("app mode");
    expect(DESKTOP).toContain("Chat and Cowork");
    expect(DESKTOP).toContain("Claude Desktop → Settings → Claude Code → Environment variables");
    expect(DESKTOP).toContain("Quit and reopen Claude Desktop");
  });

  test("the section says where app mode's configuration lives and how the key reaches Desktop without being written", () => {
    expect(DESKTOP).toContain("Claude-3p/configLibrary");
    expect(DESKTOP).toContain("splice desktop credential");
    expect(DESKTOP).toContain("/etc/claude-desktop/managed-settings.json");
  });

  test("the section points at the diagnostics and the way out by uninstalling", () => {
    expect(DESKTOP).toContain("splice doctor");
    expect(DESKTOP).toContain("splice uninstall");
  });

  test("README no longer says `splice desktop on` writes into ~/.claude/settings.json", () => {
    expect(README).not.toContain("into the `env` of `~/.claude/settings.json`");
    expect(README).not.toContain("`splice desktop on` writes Claude Code's switch, splice's door and the desktop key into");
    for (const row of fileRows("~/.claude/settings.json")) expect(row).not.toContain("While Desktop is on");
  });

  test("the files table has a row for each file app mode writes", () => {
    expect(fileRows("desktop-credential").length).toBe(1);
    expect(fileRows("configLibrary").length).toBeGreaterThanOrEqual(1);
    expect(fileRows("configLibrary").join("\n")).toContain("_meta.json");
  });

  test("CHANGELOG's Unreleased section names app mode", () => {
    const unreleased = CHANGELOG.split(/\n## /).find((s) => s.startsWith("Unreleased")) ?? "";
    expect(unreleased).toContain("splice desktop on --app");
  });
});
