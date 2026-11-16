// ledger-export: the ledger leaves the box for the team's own warehouse, as hourly JSONL files under
// `<prefix>/splice-usage/YYYY/MM/DD/HH.jsonl` (UTC), to a folder or to any S3-compatible bucket signed with SigV4.
// Exporting again rewrites the same files, so each row is in them exactly once; the daemon also exports on its own
// every `every`. The bucket's secret never lands in the server's home.
import { afterAll, beforeAll, describe, expect, test } from "bun:test";
import { mkdtempSync, readFileSync, readdirSync, rmSync, statSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, relative } from "node:path";
import { json, until } from "./lib/harness.ts";
import { S3 } from "./lib/s3.ts";
import { Server } from "./lib/server.ts";

const SECRET = `s3-secret-${crypto.randomUUID()}`;
const HOUR_FILE = /^splice-usage\/\d{4}\/\d{2}\/\d{2}\/\d{2}\.jsonl$/;

interface Row {
  at: string;
  person: string;
  device: string;
  outcome: string;
  prompt_tokens: number;
}

const lines = (text: string) =>
  text
    .split("\n")
    .filter(Boolean)
    .map((l) => JSON.parse(l) as Row);

function walk(dir: string): Record<string, string> {
  const out: Record<string, string> = {};
  const go = (d: string) => {
    for (const n of readdirSync(d)) {
      const p = join(d, n);
      if (statSync(p).isDirectory()) go(p);
      else out[relative(dir, p)] = readFileSync(p, "utf8");
    }
  };
  go(dir);
  return out;
}

/** Rows in the ledger over the last hour, everyone's. */
const count = (srv: Server) => json<{ rows: Row[] }>(srv.cli(["server", "usage", "rows", "--json", "--since", "1h"])).rows.length;

const hourOf = (iso: string) => {
  const d = new Date(iso);
  const p = (n: number) => String(n).padStart(2, "0");
  return `splice-usage/${d.getUTCFullYear()}/${p(d.getUTCMonth() + 1)}/${p(d.getUTCDate())}/${p(d.getUTCHours())}.jsonl`;
};

describe("to a folder", () => {
  let srv: Server;
  let dir = "";
  beforeAll(async () => {
    dir = mkdtempSync(join(tmpdir(), "splice-export-check-"));
    srv = await Server.boot({ toml: `\n[server.export]\nto = "file://${dir}"\nevery = "1h"\n` });
  }, 900_000);
  afterAll(async () => {
    await srv?.dispose();
    rmSync(dir, { recursive: true, force: true });
  }, 60_000);

  test("export --now writes each row once into its UTC hour's file; running it again changes nothing", async () => {
    const ann = srv.mint("ann@example.com");
    const bob = srv.mint("bob@example.com");
    for (const k of [ann, ann, bob]) expect((await srv.turn(k.key)).status).toBe(200);
    await until("3 rows", () => count(srv) >= 3, 20_000);
    expect(srv.cli(["server", "usage", "export", "--now"]).code).toBe(0);
    const first = walk(dir);
    const files = Object.keys(first);
    expect(files.length).toBeGreaterThanOrEqual(1);
    for (const f of files) expect(f).toMatch(HOUR_FILE);
    const rows = Object.values(first).flatMap(lines);
    expect(rows.length).toBe(3);
    expect(rows.filter((r) => r.person === "ann@example.com").length).toBe(2);
    expect(rows.every((r) => r.prompt_tokens === 1200 && r.outcome === "ok")).toBe(true);
    for (const [f, text] of Object.entries(first)) for (const r of lines(text)) expect(f).toBe(hourOf(r.at));
    expect(srv.cli(["server", "usage", "export", "--now"]).code).toBe(0);
    expect(walk(dir)).toEqual(first);
  }, 120_000);

  test("a later row joins its hour's file without repeating the earlier ones", async () => {
    const cy = srv.mint("cy@example.com");
    expect((await srv.turn(cy.key)).status).toBe(200);
    await until("4 rows", () => count(srv) >= 4, 20_000);
    expect(srv.cli(["server", "usage", "export", "--now"]).code).toBe(0);
    const rows = Object.values(walk(dir)).flatMap(lines);
    expect(rows.length).toBe(4);
    expect(new Set(rows.map((r) => `${r.at}|${r.device}`)).size).toBe(4);
  }, 120_000);
});

describe("to an S3-compatible bucket", () => {
  let srv: Server;
  let s3: S3;
  beforeAll(async () => {
    s3 = new S3("team-usage", "AKIDSPLICECHECK", SECRET).start();
    srv = await Server.boot({
      env: { SPLICE_CHECK_S3_KEY: "AKIDSPLICECHECK", SPLICE_CHECK_S3_SECRET: SECRET },
      toml: `
[server.export]
to = "s3://team-usage/gateway"
endpoint = "${s3.endpoint}"
region = "us-east-1"
path_style = true
access_key_env = "SPLICE_CHECK_S3_KEY"
secret_key_env = "SPLICE_CHECK_S3_SECRET"
every = "3s"
`,
    });
  }, 900_000);
  afterAll(async () => {
    await srv?.dispose();
    s3?.stop();
  }, 60_000);

  test("export --now puts signed hourly objects under the prefix", async () => {
    const k = srv.mint("dee@example.com");
    for (let i = 0; i < 2; i++) expect((await srv.turn(k.key)).status).toBe(200);
    await until("2 rows", () => count(srv) >= 2, 20_000);
    // The bucket is served by this process, so the command runs beside it: `cli` would hold the bucket still, and
    // an export that waits for its PUTs to be answered could never end.
    expect((await srv.run(["server", "usage", "export", "--now"])).code).toBe(0);
    expect(s3.refused).toEqual([]);
    const objects = s3.texts("gateway/");
    const keys = Object.keys(objects);
    expect(keys.length).toBeGreaterThanOrEqual(1);
    for (const key of keys) expect(key.slice("gateway/".length)).toMatch(HOUR_FILE);
    const rows = Object.values(objects).flatMap(lines);
    expect(rows.filter((r) => r.person === "dee@example.com").length).toBe(2);
  }, 120_000);

  test("the daemon exports on its own every `every`, and a new row reaches the bucket once", async () => {
    const k = srv.mint("eve@example.com");
    expect((await srv.turn(k.key)).status).toBe(200);
    await until(
      "eve's row in the bucket",
      () => Object.values(s3.texts("gateway/")).flatMap(lines).some((r) => r.person === "eve@example.com"),
      30_000,
    );
    const rows = Object.values(s3.texts("gateway/")).flatMap(lines);
    expect(rows.filter((r) => r.person === "eve@example.com").length).toBe(1);
    expect(rows.filter((r) => r.person === "dee@example.com").length).toBe(2);
  }, 120_000);

  test("the bucket's secret is in no file of the server's home, its log included", () => {
    expect(srv.s.files().filter((f) => f.bytes.includes(SECRET)).map((f) => f.path)).toEqual([]);
  });
});
