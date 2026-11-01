// `release publish <version> (--dist <dir> | --run <id>)` — put an accepted release on splice.torad.ai/download, where
// install.sh and `splice upgrade` download it since Oct 31, 2026 (Marcos: "I dont want the release download to be there
// [GitHub] ... being at Cloudflare makes more sense"). It runs on the maintainer's machine, through R2's S3 API with a
// token scoped to the bucket that the keyring holds, because a Cloudflare token is never set in the shell or in the
// repository's secrets.
//
//   --run <id>   the accepted-release artifact of a release.yml run, downloaded with gh. Every file its
//                sha256sums.txt names must carry release.yml's build provenance attestation before anything goes
//                up. Once the site serves every file, the run's draft GitHub release is published, which creates the
//                tag at the gated commit.
//   --dist <dir> a bundle already on disk, such as a local beta's dist/. No GitHub release is touched.
//
// The bundle's launcher must name the version being published, the same marker `release accept` pins the jar against,
// so a run's artifact can never go up under another version's folder. src/lib/downloads.ts does the rest: every file
// checked against sha256sums.txt, a published version refused, and the list written last.
import { existsSync, mkdtempSync, readFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { keyringAccess, publish, s3Store, SITE } from "../lib/downloads.ts";
import { ACCEPTED_ARTIFACT } from "../lib/workflow.ts";

export const usage =
  "publish <version> --run <id> | --dist <dir>  put an accepted release on splice.torad.ai/download; --run then publishes its draft";

/** The launcher's own release marker, the line release.yml derives the tag from. */
function launcherVersion(dist: string): string | null {
  const path = join(dist, "splice-launch");
  if (!existsSync(path)) return null;
  return /^SPLICE_GATEWAY_VERSION="([^"]+)"/m.exec(readFileSync(path, "utf8"))?.[1] ?? null;
}

const SIGNER = "torad-labs/splice/.github/workflows/release.yml";

/** The first file sha256sums.txt names that `gh attestation verify` does not bind to release.yml, as an Error. */
function unattested(dist: string, repoRoot: string): Error | undefined {
  const sums = join(dist, "sha256sums.txt");
  if (!existsSync(sums)) return new Error(`${sums} is missing`);
  const names = readFileSync(sums, "utf8").split("\n").map((l) => l.trim().split(/\s+/)[1]?.replace(/^\*/, "")).filter((n): n is string => !!n);
  for (const name of names) {
    const checked = Bun.spawnSync(["gh", "attestation", "verify", join(dist, name), "--repo", "torad-labs/splice", "--signer-workflow", SIGNER], {
      cwd: repoRoot,
      stdin: "ignore",
      stdout: "pipe",
      stderr: "pipe",
    });
    if (checked.exitCode !== 0) return new Error(`${name} has no build provenance attestation from ${SIGNER}: ${checked.stderr.toString().trim()}`);
  }
  return undefined;
}

/** Publish [dist] as v[version] on the site; an Error says why nothing was listed. */
export async function publishDist(dist: string, version: string): Promise<Error | undefined> {
  const named = launcherVersion(dist);
  if (named !== version) return new Error(`${dist}/splice-launch names ${named ?? "no version"}, not ${version}`);
  const access = keyringAccess();
  if (access instanceof Error) return access;
  try {
    return await publish(s3Store(access), { version, dist, now: () => new Date() });
  } catch (failed) {
    // An upload that failed halfway: files may be up, but the list was not written, so no client sees them.
    return new Error(`${failed instanceof Error ? failed.message : String(failed)} (releases.json was not changed)`);
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
      const refused = unattested(dist, repoRoot);
      if (refused) {
        console.error(`release publish: ${refused.message}; nothing was published`);
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
