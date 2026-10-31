// Goal (Marcos, Oct 31: "I dont want the release download to be there [GitHub] ... being at Cloudflare makes more
// sense"): publishing a built release puts it on splice.torad.ai, where the installer and `splice upgrade` find it.
//
//   - after a publish, splice.torad.ai serves every file of the release byte for byte from /v<version>/, lists the
//     release in /releases.json (a beta as a prerelease), and /latest follows only a stable release;
//   - a release whose files do not match its sha256sums.txt, or a version already published, is refused with
//     nothing uploaded and the list unchanged;
//   - no client is ever pointed at a version before all of its files are there: the list is written last.
//
// The store is in memory; the site in front of it is the real worker.
import { afterAll, describe, expect, test } from "bun:test";
import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { createHash } from "node:crypto";
import { tmpdir } from "node:os";
import { join } from "node:path";
import worker, { type Bucket } from "../downloads/worker.ts";
import { publish, type Store } from "../src/lib/downloads.ts";

const dirs: string[] = [];
afterAll(() => {
  for (const dir of dirs) rmSync(dir, { recursive: true, force: true });
});
const sha = (bytes: string) => createHash("sha256").update(bytes).digest("hex");

/** A built release: its files and the sums that bind them. */
function dist(version: string, tamper?: string): string {
  const dir = mkdtempSync(join(tmpdir(), "publish-downloads-"));
  dirs.push(dir);
  const files: Record<string, string> = {
    "splice.jar": `jar ${version}`,
    "splice-launch": `launcher ${version}`,
    "install.sh": `installer ${version}`,
    "LICENSE": "MIT",
  };
  for (const [name, body] of Object.entries(files)) writeFileSync(join(dir, name), body);
  const sums = Object.entries(files).map(([name, body]) => `${sha(name === tamper ? "other" : body)}  ${name}`).join("\n");
  writeFileSync(join(dir, "sha256sums.txt"), `${sums}\n`);
  return dir;
}

type Kept = { bytes: Uint8Array; contentType: string; cacheControl: string };

/** The bucket in memory: what publish writes, and the R2 view the worker reads. */
function memory() {
  const kept = new Map<string, Kept>();
  const writes: string[] = [];
  let incompleteAtList: string[] | null = null;
  const store: Store = {
    async get(key) {
      return kept.get(key)?.bytes ?? null;
    },
    async put(key, bytes, meta) {
      if (key === "releases.json") {
        // Every version the new list names must be whole when the list lands.
        const named = (JSON.parse(new TextDecoder().decode(bytes)) as { tag_name: string }[]).map((r) => r.tag_name);
        const sums = named.map((tag) => kept.get(`${tag}/sha256sums.txt`));
        incompleteAtList = named.filter((tag, i) => {
          const list = sums[i] ? new TextDecoder().decode(sums[i]!.bytes) : "";
          return !list || list.trim().split("\n").some((line) => !kept.has(`${tag}/${line.split(/\s+/)[1]}`));
        });
      }
      writes.push(key);
      kept.set(key, { bytes, ...meta });
    },
  };
  const bucket: Bucket = {
    async get(key) {
      const k = kept.get(key);
      return k ? { size: k.bytes.length, httpEtag: `"${key}"`, httpMetadata: { contentType: k.contentType }, body: new Response(k.bytes).body! } : null;
    },
    async head(key) {
      const k = kept.get(key);
      return k ? { size: k.bytes.length, httpEtag: `"${key}"`, httpMetadata: { contentType: k.contentType } } : null;
    },
  };
  const get = (path: string) =>
    worker.fetch(new Request(`https://splice.torad.ai${path}`, { redirect: "manual" }), { RELEASES: bucket });
  return { store, kept, writes, get, incomplete: () => incompleteAtList };
}

const now = () => new Date("2026-10-31T20:00:00Z");

describe("release publish to splice.torad.ai", () => {
  test("a published stable release is served file for file, listed, and followed by /latest", async () => {
    const site = memory();
    const built = dist("0.4.0");
    expect(await publish(site.store, { version: "0.4.0", dist: built, now })).toBeUndefined();
    for (const name of ["splice.jar", "splice-launch", "install.sh", "LICENSE", "sha256sums.txt"]) {
      const reply = await site.get(`/v0.4.0/${name}`);
      expect(reply.status).toBe(200);
      expect(await reply.text()).toBe(await Bun.file(join(built, name)).text());
    }
    const list = JSON.parse(await (await site.get("/releases.json")).text());
    expect(list).toEqual([{ tag_name: "v0.4.0", prerelease: false, draft: false, published_at: "2026-10-31T20:00:00.000Z" }]);
    expect((await site.get("/latest/splice.jar")).headers.get("location")).toBe("https://splice.torad.ai/v0.4.0/splice.jar");
    expect(await (await site.get("/install.sh")).text()).toBe("installer 0.4.0");
    expect(site.incomplete()).toEqual([]);
  });

  test("a beta is listed first as a prerelease and /latest stays on the stable release", async () => {
    const site = memory();
    await publish(site.store, { version: "0.4.0", dist: dist("0.4.0"), now });
    expect(await publish(site.store, { version: "0.4.1-beta.1", dist: dist("0.4.1-beta.1"), now })).toBeUndefined();
    const list = JSON.parse(await (await site.get("/releases.json")).text());
    expect(list.map((r: { tag_name: string; prerelease: boolean }) => [r.tag_name, r.prerelease])).toEqual([["v0.4.1-beta.1", true], ["v0.4.0", false]]);
    expect(await (await site.get("/v0.4.1-beta.1/splice.jar")).text()).toBe("jar 0.4.1-beta.1");
    expect((await site.get("/latest/splice.jar")).headers.get("location")).toBe("https://splice.torad.ai/v0.4.0/splice.jar");
    expect(site.incomplete()).toEqual([]);
  });

  test("a version already published is refused and nothing is written", async () => {
    const site = memory();
    await publish(site.store, { version: "0.4.0", dist: dist("0.4.0"), now });
    const before = [...site.writes];
    const again = await publish(site.store, { version: "0.4.0", dist: dist("0.4.0"), now });
    expect(again).toBeInstanceOf(Error);
    expect(String(again)).toContain("v0.4.0 is already published");
    expect(site.writes).toEqual(before);
  });

  test("a file that does not match sha256sums.txt, or one the sums name that is missing, refuses before any upload", async () => {
    const site = memory();
    const tampered = await publish(site.store, { version: "0.4.0", dist: dist("0.4.0", "splice.jar"), now });
    expect(String(tampered)).toContain("splice.jar does not match sha256sums.txt");
    const missing = dist("0.4.0");
    rmSync(join(missing, "LICENSE"));
    const absent = await publish(site.store, { version: "0.4.0", dist: missing, now });
    expect(String(absent)).toContain("LICENSE");
    expect(site.writes).toEqual([]);
  });

  test("files are cached as immutable and the list is never cached", async () => {
    const site = memory();
    await publish(site.store, { version: "0.4.0", dist: dist("0.4.0"), now });
    expect(site.kept.get("v0.4.0/splice.jar")?.cacheControl).toBe("public, max-age=31536000, immutable");
    expect(site.kept.get("releases.json")?.cacheControl).toBe("no-cache");
    expect(site.kept.get("releases.json")?.contentType).toBe("application/json");
  });
});
