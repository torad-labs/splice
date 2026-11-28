import { createHash } from "node:crypto";
import { existsSync, mkdirSync, readFileSync, readdirSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import type { StagedManifest } from "./dist.ts";

const PIN = "tools/release/seatd-pipe.json";
const PLATFORMS = ["linux-x86_64", "linux-aarch64", "darwin-arm64", "darwin-x86_64"] as const;
const VERSION = /^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-[a-zA-Z0-9-]+(?:\.[a-zA-Z0-9-]+)*)?$/;
const SHA256 = /^[a-f0-9]{64}$/i;

interface Asset {
  readonly asset: string;
  readonly sha256: string;
}

export interface SeatdPin {
  readonly repository: string;
  readonly version: string;
  readonly urlTemplate: string;
  readonly platforms: Readonly<Record<(typeof PLATFORMS)[number], Asset>>;
}

/** Rehearsal uses the pinned coordinates too, but must supply its own hashes from stand-in archives. */
export function readSeatdPin(repoRoot: string): SeatdPin {
  try {
    const pin = JSON.parse(readFileSync(join(repoRoot, PIN), "utf8")) as SeatdPin;
    if (new URL(pin.repository).protocol !== "https:" || !VERSION.test(pin.version)) {
      throw new Error("expected an HTTPS repository and a release version");
    }
    if (Object.keys(pin.platforms).length !== PLATFORMS.length) {
      throw new Error("expected exactly four platforms");
    }
    for (const platform of PLATFORMS) {
      const row = pin.platforms[platform];
      if (row?.asset !== `seatd-pipe-${pin.version}-${platform}.tar.gz`) {
        throw new Error(`expected the slim archive for ${platform}`);
      }
      const url = new URL(assetUrl(pin, row));
      if (url.protocol !== "https:" || !url.pathname.endsWith(`/v${pin.version}/${row.asset}`)) {
        throw new Error(`expected a versioned HTTPS download URL for ${row.asset}`);
      }
    }
    return pin;
  } catch (error) {
    throw new Error(`seatd-pipe: invalid ${PIN}: ${error instanceof Error ? error.message : String(error)}`);
  }
}

function assetUrl(pin: SeatdPin, row: Asset): string {
  return pin.urlTemplate.replaceAll("{repository}", pin.repository)
    .replaceAll("{version}", pin.version).replaceAll("{asset}", row.asset);
}

function reviewedRows(pin: SeatdPin): readonly Asset[] {
  if (pin.version.endsWith("-unreleased")) throw new Error(`seatd-pipe: no reviewed release in ${PIN}`);
  const rows = PLATFORMS.map((platform) => pin.platforms[platform]);
  for (const { asset, sha256 } of rows) {
    if (!SHA256.test(sha256) || /^0+$/.test(sha256)) {
      throw new Error(`seatd-pipe: no reviewed SHA-256 for ${asset} in ${PIN}; replace the placeholder with the reviewed release pin`);
    }
  }
  return rows;
}

/** The CI step fetches only the reviewed slim assets. Nothing from the upstream sums list is fetched. */
export async function fetchSeatdAssets(pin: SeatdPin, output: string, get: (url: string) => Promise<Response> = fetch): Promise<void> {
  const rows = reviewedRows(pin);
  mkdirSync(output, { recursive: true });
  if (readdirSync(output).length !== 0) throw new Error(`seatd-pipe: expected an empty staging directory at ${output}`);
  for (const row of rows) {
    try {
      const response = await get(assetUrl(pin, row));
      if (!response.ok) throw new Error(`download failed (HTTP ${response.status})`);
      const bytes = Buffer.from(await response.arrayBuffer());
      const actual = createHash("sha256").update(bytes).digest("hex");
      if (actual !== row.sha256.toLowerCase()) {
        throw new Error(`SHA-256 mismatch: expected ${row.sha256}, got ${actual}`);
      }
      writeFileSync(join(output, row.asset), bytes, { flag: "wx" });
    } catch (error) {
      throw new Error(`seatd-pipe: ${row.asset}: ${error instanceof Error ? error.message : String(error)}`);
    }
  }
}

/** General acceptance verifies bytes against the sums; this binds the staged sums to the reviewed input. */
export function requireSeatdAssets(dist: string, manifest: StagedManifest, pin: SeatdPin): void {
  const expected = new Set(reviewedRows(pin).map(({ asset, sha256 }) => {
    if (!existsSync(join(dist, asset)) || !manifest.sums.has(asset)) {
      throw new Error(`seatd-pipe: required companion asset missing from the release: ${asset}`);
    }
    if (manifest.sums.get(asset) !== sha256.toLowerCase()) {
      throw new Error(`seatd-pipe: staged SHA-256 does not match the pin for ${asset}`);
    }
    return asset;
  }));
  const unexpected = manifest.assets.filter((name) => /^seatd-.*\.tar\.gz$/.test(name) && !expected.has(name));
  if (unexpected.length > 0) throw new Error(`seatd-pipe: unpinned assets in the release: ${unexpected.join(", ")}`);
}
