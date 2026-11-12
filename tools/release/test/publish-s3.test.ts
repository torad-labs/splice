// Goal (review round 9 on PR #247: wrangler refuses an object over 300 MiB, and the release jar with every platform is
// about 450 MB): a release is published whatever the size of its files. Publishing goes through R2's S3 API, so the
// full jar goes up in parts and is kept byte for byte, and the list still lands only after every file is whole.
//
// R2 is a third party's network, so a small S3-speaking server stands in for it here: path-style objects, the
// multipart calls, and NoSuchKey for a missing key, the way R2 answers them.
import { afterAll, describe, expect, test } from "bun:test";
import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { createHash } from "node:crypto";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { publish, s3Store } from "../src/lib/downloads.ts";

const MiB = 1024 * 1024;
const dirs: string[] = [];
const sha = (bytes: Uint8Array) => createHash("sha256").update(bytes).digest("hex");

/** An S3 bucket in memory, spoken to over HTTP like R2: what was kept, and in what order each key became whole. */
function fakeR2() {
  const kept = new Map<string, Uint8Array>();
  const whole: string[] = [];
  const uploads = new Map<string, { key: string; parts: Map<number, Uint8Array> }>();
  let next = 0;
  const xml = (body: string, status = 200) => new Response(`<?xml version="1.0" encoding="UTF-8"?>${body}`, { status, headers: { "content-type": "application/xml" } });
  const server = Bun.serve({
    port: 0,
    maxRequestBodySize: 1024 * MiB,
    async fetch(request) {
      const url = new URL(request.url);
      const [, bucket, ...rest] = url.pathname.split("/");
      const key = decodeURIComponent(rest.join("/"));
      const keep = (k: string, bytes: Uint8Array) => {
        kept.set(k, bytes);
        whole.push(k);
      };
      if (bucket !== "splice-releases" || !key) return xml("<Error><Code>NoSuchBucket</Code></Error>", 404);
      const uploadId = url.searchParams.get("uploadId");
      if (request.method === "POST" && url.searchParams.has("uploads")) {
        const id = `upload-${next++}`;
        uploads.set(id, { key, parts: new Map() });
        return xml(`<InitiateMultipartUploadResult><Bucket>${bucket}</Bucket><Key>${key}</Key><UploadId>${id}</UploadId></InitiateMultipartUploadResult>`);
      }
      if (request.method === "PUT" && uploadId) {
        const upload = uploads.get(uploadId);
        if (!upload) return xml("<Error><Code>NoSuchUpload</Code></Error>", 404);
        const bytes = new Uint8Array(await request.arrayBuffer());
        upload.parts.set(Number(url.searchParams.get("partNumber")), bytes);
        return new Response(null, { headers: { etag: `"${sha(bytes)}"` } });
      }
      if (request.method === "POST" && uploadId) {
        const upload = uploads.get(uploadId);
        if (!upload) return xml("<Error><Code>NoSuchUpload</Code></Error>", 404);
        const named = [...(await request.text()).matchAll(/<PartNumber>(\d+)<\/PartNumber>/g)].map((m) => Number(m[1]));
        const parts = named.map((n) => upload.parts.get(n)!);
        const bytes = new Uint8Array(parts.reduce((n, p) => n + p.length, 0));
        let at = 0;
        for (const part of parts) {
          bytes.set(part, at);
          at += part.length;
        }
        keep(upload.key, bytes);
        uploads.delete(uploadId);
        return xml(`<CompleteMultipartUploadResult><Bucket>${bucket}</Bucket><Key>${key}</Key><ETag>"${sha(bytes)}"</ETag></CompleteMultipartUploadResult>`);
      }
      if (request.method === "DELETE" && uploadId) {
        uploads.delete(uploadId);
        return new Response(null, { status: 204 });
      }
      if (request.method === "PUT") {
        keep(key, new Uint8Array(await request.arrayBuffer()));
        return new Response(null, { headers: { etag: `"${sha(kept.get(key)!)}"` } });
      }
      const found = kept.get(key);
      if (request.method === "HEAD") return new Response(null, { status: found ? 200 : 404, headers: found ? { "content-length": String(found.length) } : {} });
      if (request.method === "GET") return found ? new Response(found) : xml(`<Error><Code>NoSuchKey</Code><Key>${key}</Key></Error>`, 404);
      return xml("<Error><Code>MethodNotAllowed</Code></Error>", 405);
    },
  });
  const store = s3Store({ endpoint: `http://127.0.0.1:${server.port}`, accessKeyId: "test", secretAccessKey: "test" });
  return { store, kept, whole, stop: () => server.stop(true) };
}

/** A built release whose jar is [jarBytes] long, every MiB marked with its index so a misplaced part shows. */
function dist(jarBytes: number): { dir: string; jar: Uint8Array } {
  const dir = mkdtempSync(join(tmpdir(), "publish-s3-"));
  dirs.push(dir);
  const jar = new Uint8Array(jarBytes);
  for (let i = 0; i < jarBytes; i += MiB) jar.fill(i / MiB, i, Math.min(i + MiB, jarBytes));
  const files: Record<string, Uint8Array> = {
    "splice.jar": jar,
    "splice-launch": new TextEncoder().encode("launcher"),
    "install.sh": new TextEncoder().encode("installer"),
  };
  for (const [name, bytes] of Object.entries(files)) writeFileSync(join(dir, name), bytes);
  writeFileSync(join(dir, "sha256sums.txt"), `${Object.entries(files).map(([name, bytes]) => `${sha(bytes)}  ${name}`).join("\n")}\n`);
  return { dir, jar };
}

afterAll(() => {
  for (const dir of dirs) rmSync(dir, { recursive: true, force: true });
});

describe("release publish through R2's S3 API", () => {
  test("a jar over 300 MiB is published byte for byte, and the list lands after every file", async () => {
    const r2 = fakeR2();
    try {
      const built = dist(301 * MiB + 17);
      const now = () => new Date("2026-10-31T23:00:00Z");
      expect(await publish(r2.store, { version: "0.4.0", dist: built.dir, now })).toBeUndefined();
      expect(sha(r2.kept.get("v0.4.0/splice.jar")!)).toBe(sha(built.jar));
      expect(new TextDecoder().decode(r2.kept.get("v0.4.0/install.sh"))).toBe("installer");
      const list = JSON.parse(new TextDecoder().decode(r2.kept.get("releases.json")));
      expect(list.map((r: { tag_name: string }) => r.tag_name)).toEqual(["v0.4.0"]);
      expect(r2.whole.at(-1)).toBe("releases.json");
    } finally {
      r2.stop();
    }
  }, 120_000);

  test("a bucket with no list yet reads as no release, and a second publish adds to the list", async () => {
    const r2 = fakeR2();
    try {
      const now = () => new Date("2026-10-31T23:00:00Z");
      expect(await publish(r2.store, { version: "0.4.0", dist: dist(MiB).dir, now })).toBeUndefined();
      expect(await publish(r2.store, { version: "0.4.1-beta.1", dist: dist(MiB).dir, now })).toBeUndefined();
      const list = JSON.parse(new TextDecoder().decode(r2.kept.get("releases.json")));
      expect(list.map((r: { tag_name: string }) => r.tag_name)).toEqual(["v0.4.1-beta.1", "v0.4.0"]);
      expect(String(await publish(r2.store, { version: "0.4.0", dist: dist(MiB).dir, now }))).toContain("v0.4.0 is already published");
    } finally {
      r2.stop();
    }
  });
});
