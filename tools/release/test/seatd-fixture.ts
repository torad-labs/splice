import { createHash } from "node:crypto";
import { chmodSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { readSeatdPin, type SeatdPin } from "../src/lib/seatd.ts";

/** The real pin's coordinates, but hashes from locally built stand-ins, never from upstream. */
export function seatdFixture(repoRoot: string, scratch: string) {
  const original = readSeatdPin(repoRoot);
  const sourceDir = join(scratch, "payload");
  const upstream = join(scratch, "upstream");
  mkdirSync(sourceDir);
  mkdirSync(upstream);
  writeFileSync(join(sourceDir, "LICENSE"), "MIT (rehearsal fixture)\n");
  writeFileSync(join(sourceDir, "THIRD_PARTY_NOTICES.md"), "# Fixture notices\n");
  const platforms = { ...original.platforms };
  const urls = new Map<string, string>();
  for (const platform of Object.keys(platforms) as (keyof typeof platforms)[]) {
    const { asset } = platforms[platform];
    const program = join(sourceDir, "seatd-pipe");
    writeFileSync(program, `#!/bin/sh\nprintf '%s\\n' '${original.version} ${platform}'\n`);
    chmodSync(program, 0o755);
    const archive = join(upstream, asset);
    const result = Bun.spawnSync(
      ["tar", "--format=ustar", "-czf", archive, "-C", sourceDir, "seatd-pipe", "LICENSE", "THIRD_PARTY_NOTICES.md"],
      { stderr: "pipe" },
    );
    if (result.exitCode !== 0) throw new Error(`could not build ${asset}: ${result.stderr.toString()}`);
    const sha256 = createHash("sha256").update(readFileSync(archive)).digest("hex");
    platforms[platform] = { asset, sha256 };
    urls.set(`${original.repository}/releases/download/v${original.version}/${asset}`, archive);
  }

  const pin: SeatdPin = { ...original, platforms };
  const fetcher = async (input: URL | Request | string): Promise<Response> => {
    const path = urls.get(String(input));
    return path === undefined ? new Response("not a slim release asset", { status: 404 }) : new Response(Bun.file(path));
  };
  return { pin, fetcher, upstream };
}
