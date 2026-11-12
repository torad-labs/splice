// Goal (Marcos, Oct 31: "I dont want the release download to be there [GitHub] ... being at Cloudflare makes more
// sense"), then: "Splice.torad.ai should be splices website ... Have tje download be splice.torad.ai/download"):
// splice.torad.ai/download serves every release file the installer and `splice upgrade` ask for.
//
//   /download/v<version>/<file>  the file as published, cacheable forever since a published version never changes;
//   /download/latest/<file>      a redirect to that file in the newest stable release, so every client lands on one;
//   /download/install.sh         the newest stable release's installer;
//   /download/releases.json      the list of published releases the updater reads, never cached;
//
// and nothing else under /download. The rest of the host is splice's website (Marcos, Oct 31: "Yes, at splice.torad.ai,
// with downloads at /download"): every other path is the site's own files, and no site file ever answers for a
// /download path. The worker runs here against an in-memory bucket with the R2 calls it makes, and a stand-in for the
// site's static assets.
import { describe, expect, test } from "bun:test";
import worker, { type Bucket, newestStable } from "../downloads/worker.ts";

type Entry = { tag_name: string; prerelease: boolean; draft: boolean; published_at: string };
const entry = (tag: string): Entry => ({ tag_name: tag, prerelease: tag.includes("-"), draft: false, published_at: "2026-10-31T00:00:00Z" });

function bucket(objects: Record<string, string>): Bucket {
  const meta = (key: string) => ({ size: objects[key]!.length, httpEtag: `"${key}"`, httpMetadata: { contentType: "application/octet-stream" } });
  return {
    async get(key) {
      return key in objects ? { ...meta(key), body: new Response(objects[key]).body! } : null;
    },
    async head(key) {
      return key in objects ? meta(key) : null;
    },
  };
}

/** The website's static assets: [files] by path, 404 for anything else, the way the assets binding answers. Inside the
 *  worker the binding's reply carries no content-length (measured on splice.torad.ai, Oct 31), so neither does this. */
function assets(files: Record<string, string>) {
  return {
    async fetch(request: Request) {
      const path = new URL(request.url).pathname;
      if (!(path in files)) return new Response("site: not found", { status: 404 });
      const bytes = new TextEncoder().encode(files[path]);
      const body = request.method === "HEAD" ? null : new ReadableStream<Uint8Array>({ start(c) { c.enqueue(bytes); c.close(); } });
      return new Response(body, { headers: { "content-type": "text/html; charset=utf-8" } });
    },
  };
}

const feed = (...tags: string[]) => JSON.stringify(tags.map(entry));
const site = (objects: Record<string, string>, pages: Record<string, string> = {}) => (path: string, method = "GET") =>
  worker.fetch(new Request(`https://splice.torad.ai${path}`, { method, redirect: "manual" }), { RELEASES: bucket(objects), ASSETS: assets(pages) });

describe("splice.torad.ai", () => {
  const objects = {
    "releases.json": feed("v0.4.1-beta.1", "v0.4.0", "v0.3.2"),
    "v0.4.0/splice.jar": "jar 0.4.0",
    "v0.4.0/install.sh": "installer 0.4.0",
    "v0.4.1-beta.1/splice.jar": "jar beta",
    "v0.3.2/splice.jar": "jar 0.3.2",
  };
  const get = site(objects);

  test("a version's file is served as published and cached as immutable", async () => {
    const reply = await get("/download/v0.4.0/splice.jar");
    expect(reply.status).toBe(200);
    expect(await reply.text()).toBe("jar 0.4.0");
    expect(reply.headers.get("cache-control")).toBe("public, max-age=31536000, immutable");
  });

  test("/latest/<file> redirects to the newest stable release, never a beta", async () => {
    const reply = await get("/download/latest/splice.jar");
    expect(reply.status).toBe(302);
    expect(reply.headers.get("location")).toBe("https://splice.torad.ai/download/v0.4.0/splice.jar");
    expect(reply.headers.get("cache-control")).toBe("no-store");
  });

  test("/install.sh is the newest stable release's installer, revalidated on every fetch", async () => {
    const reply = await get("/download/install.sh");
    expect(reply.status).toBe(200);
    expect(await reply.text()).toBe("installer 0.4.0");
    expect(reply.headers.get("cache-control")).toBe("no-cache");
  });

  test("/releases.json is the published list, never cached", async () => {
    const reply = await get("/download/releases.json");
    expect(reply.status).toBe(200);
    expect(JSON.parse(await reply.text()).map((r: Entry) => r.tag_name)).toEqual(["v0.4.1-beta.1", "v0.4.0", "v0.3.2"]);
    expect(reply.headers.get("cache-control")).toBe("no-cache");
  });

  test("HEAD answers like GET without a body", async () => {
    const reply = await get("/download/v0.4.0/splice.jar", "HEAD");
    expect(reply.status).toBe(200);
    expect(await reply.text()).toBe("");
  });

  test("a missing file, an unknown path or a path that leaves its folder is 404, and a write is 405", async () => {
    for (const path of ["/download/v0.4.0/missing", "/download/v9.9.9/splice.jar", "/download/secret", "/secret", "/download/v0.4.0/..%2Freleases.json", "/download/latest/..%2Fx", "/download/v0.4.0/a/b", "/v0.4.0/splice.jar"]) {
      expect((await get(path)).status).toBe(404);
    }
    expect((await get("/download/v0.4.0/splice.jar", "PUT")).status).toBe(405);
  });

  test("before any stable release, /latest and /install.sh say so instead of serving a beta", async () => {
    const early = site({ "releases.json": feed("v0.4.0-beta.3"), "v0.4.0-beta.3/install.sh": "beta installer" });
    for (const path of ["/download/latest/splice.jar", "/download/install.sh"]) {
      const reply = await early(path);
      expect(reply.status).toBe(404);
      const said = await reply.text();
      expect(said).toContain("no stable release");
      expect(said).toContain("https://splice.torad.ai/download/v<version>/install.sh");
    }
  });
});

describe("the rest of splice.torad.ai", () => {
  const pages = { "/": "<h1>splice</h1>", "/assets/site.css": "body{}", "/download/v0.4.0/splice.jar": "a page posing as the jar", "/download/secret": "a page under /download" };
  const get = site({ "releases.json": feed("v0.4.0"), "v0.4.0/splice.jar": "jar 0.4.0" }, pages);

  test("is splice's website: the bare host and every path outside /download are the site's own files", async () => {
    const home = await get("/");
    expect(home.status).toBe(200);
    expect(await home.text()).toBe("<h1>splice</h1>");
    expect(await (await get("/assets/site.css")).text()).toBe("body{}");
    expect((await get("/no-such-page")).status).toBe(404);
  });

  test("never lets a site file answer for a download path", async () => {
    expect(await (await get("/download/v0.4.0/splice.jar")).text()).toBe("jar 0.4.0");
    const stray = await get("/download/secret");
    expect(stray.status).toBe(404);
    expect(await stray.text()).not.toContain("a page under /download");
  });

  // Apple's media guidance: a server hosting video for Safari must answer byte-range requests, or the film never plays
  // on an iPhone. The assets binding answers the whole file, so the worker cuts the range out of it.
  test("answers a byte range of a site file, as Safari asks for the film", async () => {
    const film = Array.from({ length: 100 }, (_, i) => String.fromCharCode(33 + (i % 90))).join("");
    const env = { RELEASES: bucket({}), ASSETS: assets({ "/film.mp4": film }) };
    const ask = (range?: string) =>
      worker.fetch(new Request("https://splice.torad.ai/film.mp4", { headers: range ? { range } : {} }), env);
    for (const [range, start, end] of [["bytes=10-19", 10, 19], ["bytes=95-", 95, 99], ["bytes=-5", 95, 99], ["bytes=90-500", 90, 99], ["bytes=0-0", 0, 0]] as const) {
      const reply = await ask(range);
      expect(reply.status).toBe(206);
      expect(reply.headers.get("content-range")).toBe(`bytes ${start}-${end}/100`);
      expect(reply.headers.get("content-length")).toBe(String(end - start + 1));
      expect(await reply.text()).toBe(film.slice(start, end + 1));
    }
    const whole = await ask();
    expect(whole.status).toBe(200);
    expect(whole.headers.get("accept-ranges")).toBe("bytes");
    expect(await whole.text()).toBe(film);
    const past = await ask("bytes=100-");
    expect(past.status).toBe(416);
    expect(past.headers.get("content-range")).toBe("bytes */100");
  });

  // Property: whatever chunks the file arrives in, a satisfiable range answers exactly the bytes it names.
  test("a range answers exactly its bytes, whatever chunks the file arrives in", async () => {
    const rand = (n: number) => Math.floor(Math.random() * n);
    for (let round = 0; round < 300; round++) {
      const size = 1 + rand(300);
      const file = Uint8Array.from({ length: size }, (_, i) => (i * 7 + round) % 256);
      const cuts = [...new Set(Array.from({ length: rand(6) }, () => rand(size)))].sort((a, b) => a - b);
      const chunks = [0, ...cuts, size].slice(1).map((end, i, ends) => file.slice(i === 0 ? 0 : ends[i - 1], end)).filter((c) => c.length);
      const ASSETS = {
        async fetch() {
          const body = new ReadableStream<Uint8Array>({ start(c) { for (const chunk of chunks) c.enqueue(chunk); c.close(); } });
          return new Response(body);
        },
      };
      const start = rand(size);
      const end = start + rand(size - start);
      const reply = await worker.fetch(new Request("https://splice.torad.ai/f.bin", { headers: { range: `bytes=${start}-${end}` } }), { RELEASES: bucket({}), ASSETS });
      expect(reply.status).toBe(206);
      expect([...new Uint8Array(await reply.arrayBuffer())]).toEqual([...file.slice(start, end + 1)]);
    }
  });

  test("is never written to", async () => {
    for (const method of ["PUT", "POST", "DELETE"]) expect((await get("/", method)).status).toBe(405);
  });
});

describe("the newest stable release", () => {
  // Property: over any published list, in any order, the newest stable is the highest X.Y.Z among the entries
  // that are not prereleases, and build metadata never decides it.
  const rand = (n: number) => Math.floor(Math.random() * n);
  test("is the highest stable version, whatever order the list is in", () => {
    for (let round = 0; round < 500; round++) {
      const versions = Array.from({ length: 1 + rand(8) }, () => [rand(3), rand(12), rand(12)] as const);
      const tags = versions.map(([a, b, c]) => {
        const kind = rand(3);
        return kind === 0 ? `v${a}.${b}.${c}-beta.${rand(5)}` : kind === 1 ? `v${a}.${b}.${c}+build.${rand(9)}` : `v${a}.${b}.${c}`;
      });
      const shuffled = [...tags].sort(() => Math.random() - 0.5).map(entry);
      const stable = versions.filter((_, i) => !tags[i]!.includes("-"));
      const want = stable.reduce<readonly [number, number, number] | null>(
        (best, v) => (!best || v[0] > best[0] || (v[0] === best[0] && (v[1] > best[1] || (v[1] === best[1] && v[2] > best[2]))) ? v : best),
        null,
      );
      const got = newestStable(shuffled);
      if (!want) expect(got).toBeNull();
      else expect(got?.replace(/^v/, "").replace(/\+.*$/, "")).toBe(want.join("."));
    }
  });

  test("ignores drafts and entries whose tag is not a version", () => {
    expect(newestStable([{ ...entry("v9.0.0"), draft: true }, entry("latest"), entry("v0.4.0")])).toBe("v0.4.0");
  });
});
