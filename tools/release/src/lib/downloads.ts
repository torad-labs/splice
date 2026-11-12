// Publishing a built release to splice.torad.ai/download (Marcos, Oct 31, 2026: "I dont want the release download to be there
// [GitHub] ... being at Cloudflare makes more sense"). The release's files go into the splice-releases R2 bucket under
// v<version>/, and the bucket's releases.json lists the release; the worker in tools/release/downloads/worker.ts serves
// both. The order is what keeps a client from ever seeing half a release:
//
//   1. every file the release's sha256sums.txt names is read from dist/ and checked against its sum, so a release
//      that does not match its sums is refused before anything is written;
//   2. a version the list already names is refused: a published version never changes, which is why its files are
//      cached for a year;
//   3. the files go up, then sha256sums.txt, then the list with the release at its head. Until the list lands no
//      client is pointed at the version, so an upload that dies halfway leaves only files nobody asks for.
//
// The store is the bucket seen through R2's S3 API, or memory in the tests. Not wrangler: `wrangler r2 object put`
// refuses an object over 300 MiB, and the jar with every platform is about 450 MB, so the S3 store sends it in parts.
// Its credentials are a token scoped to this one bucket, kept in this machine's keyring and read in-process, never
// set in the shell. The worker sets every cache policy itself, so an object carries only its content type.
import { existsSync, readFileSync } from "node:fs";
import { createHash } from "node:crypto";
import { join } from "node:path";

export const BUCKET = "splice-releases";
export const SITE = "https://splice.torad.ai/download";
const LIST = "releases.json";
const SUMS = "sha256sums.txt";
const VERSION = /^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(-[0-9A-Za-z.-]+)?$/;
const FILE = /^[A-Za-z0-9][A-Za-z0-9._+-]*$/;

/** The bucket as publish needs it: read one object, or write one with its content type. */
export interface Store {
  get(key: string): Promise<Uint8Array | null>;
  put(key: string, bytes: Uint8Array, meta: { contentType: string }): Promise<void>;
}

/** Where the bucket's S3 API answers and the key pair that signs for it. */
export type S3Access = { endpoint: string; accessKeyId: string; secretAccessKey: string };

/** One entry of releases.json, in the shape of GitHub's releases list, which the updater already reads. */
export type Listed = { tag_name: string; prerelease: boolean; draft: false; published_at: string };

function contentType(name: string): string {
  if (name.endsWith(".jar")) return "application/java-archive";
  if (name.endsWith(".sh")) return "text/x-shellscript; charset=utf-8";
  if (name.endsWith(".json")) return "application/json";
  if (name.endsWith(".txt") || name.endsWith(".md") || name === "LICENSE") return "text/plain; charset=utf-8";
  return "application/octet-stream";
}

/** The files sha256sums.txt binds, each read and checked; an Error names the first one that is missing or wrong. */
function boundFiles(dist: string): Map<string, Uint8Array> | Error {
  const sumsPath = join(dist, SUMS);
  if (!existsSync(sumsPath)) return new Error(`${SUMS} is missing from ${dist}`);
  const files = new Map<string, Uint8Array>();
  for (const line of readFileSync(sumsPath, "utf8").split("\n").filter((l) => l.trim())) {
    const [hash, name, extra] = line.trim().split(/\s+/);
    const file = name?.replace(/^\*/, "");
    if (!hash || !file || extra !== undefined || !/^[0-9a-f]{64}$/.test(hash) || !FILE.test(file) || file === SUMS) {
      return new Error(`${SUMS} has a line that is not "<sha256>  <file>": ${line}`);
    }
    const path = join(dist, file);
    if (!existsSync(path)) return new Error(`${file} is named in ${SUMS} but missing from ${dist}`);
    const bytes = new Uint8Array(readFileSync(path));
    if (createHash("sha256").update(bytes).digest("hex") !== hash) return new Error(`${file} does not match ${SUMS}`);
    files.set(file, bytes);
  }
  if (files.size === 0) return new Error(`${SUMS} names no file`);
  return files;
}

async function listed(store: Store): Promise<Listed[] | Error> {
  const bytes = await store.get(LIST);
  if (!bytes) return [];
  try {
    const parsed: unknown = JSON.parse(new TextDecoder().decode(bytes));
    if (!Array.isArray(parsed)) return new Error(`${LIST} in ${BUCKET} is not a list`);
    return parsed as Listed[];
  } catch (error) {
    return new Error(`${LIST} in ${BUCKET} is not JSON: ${(error as Error).message}`);
  }
}

/** Put the release built in [dist] on splice.torad.ai/download as v[version]; undefined when it is there, an Error when not. */
export async function publish(store: Store, release: { version: string; dist: string; now: () => Date }): Promise<Error | undefined> {
  const { version, dist, now } = release;
  if (!VERSION.test(version)) return new Error(`"${version}" is not a release version (X.Y.Z or X.Y.Z-pre)`);
  const tag = `v${version}`;
  const files = boundFiles(dist);
  if (files instanceof Error) return files;
  const list = await listed(store);
  if (list instanceof Error) return list;
  if (list.some((r) => r.tag_name === tag)) return new Error(`${tag} is already published; a published version never changes`);

  for (const [name, bytes] of files) await store.put(`${tag}/${name}`, bytes, { contentType: contentType(name) });
  await store.put(`${tag}/${SUMS}`, new Uint8Array(readFileSync(join(dist, SUMS))), { contentType: contentType(SUMS) });
  const entry: Listed = { tag_name: tag, prerelease: version.includes("-"), draft: false, published_at: now().toISOString() };
  const next = new TextEncoder().encode(`${JSON.stringify([entry, ...list], null, 2)}\n`);
  await store.put(LIST, next, { contentType: "application/json" });
  return undefined;
}

/** The keyring entry holding the S3 access of a token scoped to the splice-releases bucket. */
export const KEYRING = ["service", "cloudflare-torad-r2", "key", "splice-releases-s3"] as const;

/** The bucket's S3 access from this machine's keyring; an Error says what is missing, never what the entry holds. */
export function keyringAccess(): S3Access | Error {
  const found = Bun.spawnSync(["secret-tool", "lookup", ...KEYRING], { stdin: "ignore", stdout: "pipe", stderr: "pipe" });
  const where = `the keyring entry ${KEYRING.join(" ")}`;
  if (found.exitCode !== 0 || found.stdout.length === 0) return new Error(`${where} is missing: it holds the S3 access of a token scoped to ${BUCKET}`);
  try {
    const access = JSON.parse(found.stdout.toString()) as Partial<S3Access>;
    if (typeof access.endpoint === "string" && typeof access.accessKeyId === "string" && typeof access.secretAccessKey === "string") {
      return { endpoint: access.endpoint, accessKeyId: access.accessKeyId, secretAccessKey: access.secretAccessKey };
    }
  } catch {
    // reported below, without the entry's contents
  }
  return new Error(`${where} is not {endpoint, accessKeyId, secretAccessKey}`);
}

const PART = 64 * 1024 * 1024;

/** The splice-releases bucket through R2's S3 API. A file goes up in parts of 64 MiB, four at a time. */
export function s3Store(access: S3Access): Store {
  const client = new Bun.S3Client({ ...access, bucket: BUCKET, region: "auto" });
  return {
    async get(key) {
      try {
        return await client.file(key).bytes();
      } catch (failed) {
        if ((failed as { code?: unknown }).code === "NoSuchKey") return null;
        throw new Error(`reading ${BUCKET}/${key} failed: ${failed instanceof Error ? failed.message : String(failed)}`);
      }
    },
    async put(key, bytes, meta) {
      const writer = client.file(key).writer({ type: meta.contentType, partSize: PART, queueSize: 4, retry: 3 });
      try {
        writer.write(bytes);
        await writer.end();
      } catch (failed) {
        throw new Error(`writing ${BUCKET}/${key} failed: ${failed instanceof Error ? failed.message : String(failed)}`);
      }
    },
  };
}
