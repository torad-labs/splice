// splice.torad.ai — where splice's releases are downloaded from since Oct 31, 2026 (Marcos: "I dont want the release
// download to be there [GitHub] ... being at Cloudflare makes more sense"). A Cloudflare Worker in front of the
// splice-releases R2 bucket, which `bun tools/release` publishes into (src/lib/downloads.ts):
//
//   /v<version>/<file>  a published file; a version never changes once published, so it is cached for a year
//   /latest/<file>      302 to <file> in the newest stable release: one pointer, so every client of a run lands on
//                       one version, the way GitHub's releases/latest/download did
//   /install.sh         the newest stable release's installer, served directly for `curl … | bash`
//   /releases.json      the published list the updater reads (ReleaseChannel.kt), newest first, never cached
//
// Anything else is 404, and anything but GET and HEAD is 405. The worker reads the bucket and never writes it.

/** The part of an R2 bucket binding the worker uses. */
export interface Bucket {
  get(key: string): Promise<{ body: ReadableStream; size: number; httpEtag?: string; httpMetadata?: { contentType?: string } } | null>;
  head(key: string): Promise<{ size: number; httpEtag?: string; httpMetadata?: { contentType?: string } } | null>;
}

interface Env {
  RELEASES: Bucket;
}

const LIST = "releases.json";
const IMMUTABLE = "public, max-age=31536000, immutable";
const REVALIDATE = "no-cache";
const VERSION = /^v(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(-[0-9A-Za-z.-]+)?(\+[0-9A-Za-z.-]+)?$/;
const FILE = /^[A-Za-z0-9][A-Za-z0-9._+-]*$/;
const HOME =
  "splice downloads\n\nInstall the newest release:\n\n  curl -fsSL https://splice.torad.ai/install.sh | bash\n\n" +
  "Every release lives under /v<version>/, the newest stable under /latest/, and the list in /releases.json.\n";

type Release = { tag_name?: unknown; prerelease?: unknown; draft?: unknown };

/** The highest stable version in the list, by X.Y.Z: drafts, prereleases and non-version tags never count, and build
 *  metadata never decides. Null when the list has no stable release. */
export function newestStable(releases: readonly Release[]): string | null {
  let best: { tag: string; key: readonly number[] } | null = null;
  for (const r of releases) {
    const tag = typeof r.tag_name === "string" ? r.tag_name : "";
    const m = VERSION.exec(tag);
    if (!m || r.draft === true || r.prerelease === true || m[4]) continue;
    const key = [Number(m[1]), Number(m[2]), Number(m[3])];
    if (!best || greater(key, best.key)) best = { tag, key };
  }
  return best?.tag ?? null;
}

function greater(a: readonly number[], b: readonly number[]): boolean {
  for (let i = 0; i < a.length; i++) if (a[i] !== b[i]) return a[i]! > b[i]!;
  return false;
}

async function list(env: Env): Promise<Release[]> {
  const object = await env.RELEASES.get(LIST);
  if (!object) return [];
  const parsed: unknown = JSON.parse(await new Response(object.body).text());
  return Array.isArray(parsed) ? parsed : [];
}

function text(status: number, body: string): Response {
  return new Response(body, { status, headers: { "content-type": "text/plain; charset=utf-8", "cache-control": REVALIDATE } });
}

/** One object from the bucket, with its cache policy; HEAD reads metadata only. */
async function object(env: Env, key: string, head: boolean, cacheControl: string, contentType?: string): Promise<Response> {
  const full = head ? null : await env.RELEASES.get(key);
  const found = full ?? (head ? await env.RELEASES.head(key) : null);
  if (!found) return text(404, `not found: /${key}\n`);
  const headers = new Headers({
    "content-type": contentType ?? found.httpMetadata?.contentType ?? "application/octet-stream",
    "content-length": String(found.size),
    "cache-control": cacheControl,
  });
  if (found.httpEtag) headers.set("etag", found.httpEtag);
  return new Response(full?.body ?? null, { status: 200, headers });
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    if (request.method !== "GET" && request.method !== "HEAD") {
      return new Response("method not allowed\n", { status: 405, headers: { allow: "GET, HEAD" } });
    }
    const head = request.method === "HEAD";
    const url = new URL(request.url);
    const parts = url.pathname.split("/").slice(1);

    if (url.pathname === "/") return text(200, HOME);
    if (url.pathname === `/${LIST}`) {
      const found = await object(env, LIST, head, REVALIDATE, "application/json");
      return found.status === 404 ? new Response(head ? null : "[]", { headers: { "content-type": "application/json", "cache-control": REVALIDATE } }) : found;
    }
    if (url.pathname === "/install.sh" || (parts.length === 2 && parts[0] === "latest")) {
      const newest = newestStable(await list(env));
      if (!newest) return text(404, "no stable release is published yet; pin one with SPLICE_VERSION=v<version>\n");
      if (url.pathname === "/install.sh") return object(env, `${newest}/install.sh`, head, REVALIDATE, "text/x-shellscript; charset=utf-8");
      if (!FILE.test(parts[1]!)) return text(404, "not found\n");
      return new Response(null, { status: 302, headers: { location: `${url.origin}/${newest}/${parts[1]}`, "cache-control": "no-store" } });
    }
    if (parts.length === 2 && VERSION.test(parts[0]!) && FILE.test(parts[1]!)) {
      return object(env, `${parts[0]}/${parts[1]}`, head, IMMUTABLE);
    }
    return text(404, "not found\n");
  },
};
