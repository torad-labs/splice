import { afterEach, expect, test } from "bun:test";
import { mkdtempSync, readFileSync, readdirSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { layout } from "../../gate/src/lib/repo.ts";
import { readManifest, SUMS, type StagedManifest } from "../src/lib/dist.ts";
import { fetchSeatdAssets, requireSeatdAssets } from "../src/lib/seatd.ts";
import { seatdFixture } from "./seatd-fixture.ts";

const { repoRoot } = layout();
const scratch: string[] = [];
afterEach(() => {
  for (const dir of scratch.splice(0)) rmSync(dir, { recursive: true, force: true });
});

function fixture() {
  const dir = mkdtempSync(join(tmpdir(), "release-seatd-test-"));
  scratch.push(dir);
  return { ...seatdFixture(repoRoot, dir), staged: join(dir, "staged") };
}

test("only the four reviewed slim archives are carried through into the release", async () => {
  const { pin, staged, upstream, fetcher } = fixture();
  await fetchSeatdAssets(pin, staged, fetcher);

  const rows = Object.values(pin.platforms);
  expect(readdirSync(staged).sort()).toEqual(rows.map((row) => row.asset).sort());
  for (const { asset } of rows) {
    expect(readFileSync(join(staged, asset)).equals(readFileSync(join(upstream, asset)))).toBe(true);
  }
  writeFileSync(join(staged, SUMS), rows.map(({ asset, sha256 }) => `${sha256}  ${asset}\n`).join(""));
  const manifest = readManifest(staged);
  expect(manifest).not.toBeInstanceOf(Error);
  expect(() => requireSeatdAssets(staged, manifest as StagedManifest, pin)).not.toThrow();
});

test("a placeholder pin prevents fetching any release assets", async () => {
  const { pin, staged } = fixture();
  const row = pin.platforms["darwin-x86_64"];
  const pending = { ...pin, platforms: { ...pin.platforms, "darwin-x86_64": { ...row, sha256: "0".repeat(64) } } };
  const fetcher = async () => { throw new Error("upstream is not published"); };
  await expect(fetchSeatdAssets(pending, staged, fetcher)).rejects.toThrow(`no reviewed SHA-256 for ${row.asset}`);
});

test("a checksum mismatch names the asset and does not stage it", async () => {
  const { pin, staged } = fixture();
  await expect(fetchSeatdAssets(pin, staged, async () => new Response("not the pinned bytes")))
    .rejects.toThrow(`${pin.platforms["linux-x86_64"].asset}: SHA-256 mismatch`);
  expect(readdirSync(staged)).toEqual([]);
});

test("missing archives or newly recomputed sums cannot pass release acceptance", async () => {
  const { pin, staged, fetcher } = fixture();
  await fetchSeatdAssets(pin, staged, fetcher);
  const rows = Object.values(pin.platforms);
  const manifest = { assets: rows.map((row) => row.asset), sums: new Map(rows.map(({ asset, sha256 }) => [asset, sha256])) };
  const first = rows[0]!.asset;
  manifest.sums.set(first, "f".repeat(64));
  expect(() => requireSeatdAssets(staged, manifest, pin)).toThrow(`staged SHA-256 does not match the pin for ${first}`);
  manifest.sums.delete(first);
  expect(() => requireSeatdAssets(staged, manifest, pin)).toThrow(`required companion asset missing from the release: ${first}`);
});
