// `release publish <version> (--dist <dir> | --run <id>)` — put an accepted release on splice.torad.ai, where install.sh
// and `splice upgrade` download it since Oct 31, 2026 (Marcos: "I dont want the release download to be there [GitHub]
// ... being at Cloudflare makes more sense"). It runs on the maintainer's machine with wrangler's own login, because
// a Cloudflare token is never set in the shell or in the repository's secrets.
//
//   --run <id>   the accepted-release artifact of a release.yml run, downloaded with gh. Once the site serves every
//                file, the run's draft GitHub release is published, which creates the tag at the gated commit.
//   --dist <dir> a bundle already on disk, such as a local beta's dist/. No GitHub release is touched.
//
// The bundle's launcher must name the version being published, the same marker `release accept` pins the jar against,
// so a run's artifact can never go up under another version's folder. src/lib/downloads.ts does the rest: every file
// checked against sha256sums.txt, a published version refused, and the list written last.
import { existsSync, mkdtempSync, readFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { publish, SITE, wranglerStore } from "../lib/downloads.ts";
import { ACCEPTED_ARTIFACT } from "../lib/workflow.ts";

export const usage =
  "publish <version> --run <id> | --dist <dir>  put an accepted release on splice.torad.ai (wrangler's login); --run then publishes its draft";

/** The launcher's own release marker, the line release.yml derives the tag from. */
function launcherVersion(dist: string): string | null {
  const path = join(dist, "splice-launch");
  if (!existsSync(path)) return null;
  return /^SPLICE_GATEWAY_VERSION="([^"]+)"/m.exec(readFileSync(path, "utf8"))?.[1] ?? null;
}

/** Publish [dist] as v[version] on the site; an Error says why nothing was listed. */
export async function publishDist(dist: string, version: string): Promise<Error | undefined> {
  const named = launcherVersion(dist);
  if (named !== version) return new Error(`${dist}/splice-launch names ${named ?? "no version"}, not ${version}`);
  const scratch = mkdtempSync(join(tmpdir(), "splice-publish-"));
  try {
    return await publish(wranglerStore(scratch), { version, dist, now: () => new Date() });
  } catch (failed) {
    // A wrangler call that failed halfway: files may be up, but the list was not written, so no client sees them.
    return new Error(`${failed instanceof Error ? failed.message : String(failed)} (releases.json was not changed)`);
  } finally {
    rmSync(scratch, { recursive: true, force: true });
  }
}

export async function publishCommand(argv: string[], repoRoot: string): Promise<number> {
  const [version, flag, value, ...rest] = argv;
  if (!version || !value || rest.length || (flag !== "--run" && flag !== "--dist")) {
    console.error(`usage: bun tools/release ${usage}`);
    return 2;
  }
  const tag = `v${version.replace(/^v/, "")}`;
  const plain = tag.slice(1);
  let dist = value;
  let downloaded: string | null = null;
  try {
    if (flag === "--run") {
      downloaded = mkdtempSync(join(tmpdir(), "splice-publish-run-"));
      dist = downloaded;
      const got = Bun.spawnSync(["gh", "run", "download", value, "-n", ACCEPTED_ARTIFACT, "-D", downloaded], { cwd: repoRoot, stdout: "inherit", stderr: "inherit" });
      if (got.exitCode !== 0) {
        console.error(`release publish: gh run download ${value} -n ${ACCEPTED_ARTIFACT} failed; nothing was published`);
        return 1;
      }
    }
    const refused = await publishDist(dist, plain);
    if (refused) {
      console.error(`release publish: ${refused.message}`);
      return 1;
    }
    console.log(`release publish: ${tag} is on ${SITE}/${tag}/ and listed in ${SITE}/releases.json`);
    if (flag === "--run") {
      const made = Bun.spawnSync(["gh", "release", "edit", tag, "--draft=false"], { cwd: repoRoot, stdout: "inherit", stderr: "inherit" });
      if (made.exitCode !== 0) {
        console.error(`release publish: the files are on ${SITE}, but publishing the ${tag} draft on GitHub failed; run gh release edit ${tag} --draft=false`);
        return 1;
      }
      console.log(`release publish: the ${tag} release notes are public on GitHub`);
    }
    return 0;
  } finally {
    if (downloaded) rmSync(downloaded, { recursive: true, force: true });
  }
}
