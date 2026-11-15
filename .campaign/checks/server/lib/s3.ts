// An S3-compatible bucket for the export checks, path-style like MinIO or R2 behind a custom endpoint. It keeps
// objects in memory and answers PUT, GET and HEAD only when the request carries a valid AWS Signature Version 4 for
// its access key, so an export that sends no signature, or a wrong one, never lands.

import { createHash, createHmac } from "node:crypto";

export interface Put {
  key: string;
  bytes: number;
  at: number;
}

export class S3 {
  readonly bucket: string;
  readonly accessKey: string;
  readonly secretKey: string;
  readonly region: string;
  readonly objects = new Map<string, Uint8Array>();
  readonly puts: Put[] = [];
  /** Requests refused for a missing or bad signature, with the reason. */
  readonly refused: string[] = [];
  private server: ReturnType<typeof Bun.serve> | null = null;

  constructor(bucket: string, accessKey: string, secretKey: string, region = "us-east-1") {
    this.bucket = bucket;
    this.accessKey = accessKey;
    this.secretKey = secretKey;
    this.region = region;
  }

  get endpoint(): string {
    return `http://127.0.0.1:${this.server!.port}`;
  }

  start(): this {
    this.server = Bun.serve({ hostname: "127.0.0.1", port: 0, fetch: (req) => this.handle(req) });
    return this;
  }

  stop(): void {
    this.server?.stop(true);
    this.server = null;
  }

  /** The text of every object under this key prefix, by key. */
  texts(prefix = ""): Record<string, string> {
    const out: Record<string, string> = {};
    for (const [k, v] of this.objects) if (k.startsWith(prefix)) out[k] = new TextDecoder().decode(v);
    return out;
  }

  private async handle(req: Request): Promise<Response> {
    const url = new URL(req.url);
    const body = new Uint8Array(await req.arrayBuffer());
    const why = this.verify(req, url, body);
    if (why) {
      this.refused.push(`${req.method} ${url.pathname}: ${why}`);
      return new Response(`<Error><Code>SignatureDoesNotMatch</Code><Message>${why}</Message></Error>`, { status: 403 });
    }
    const parts = url.pathname.split("/").filter(Boolean).map(decodeURIComponent);
    if (parts[0] !== this.bucket) return new Response("<Error><Code>NoSuchBucket</Code></Error>", { status: 404 });
    const key = parts.slice(1).join("/");
    if (req.method === "PUT" && key) {
      const streamed = (req.headers.get("x-amz-content-sha256") ?? "").startsWith("STREAMING-");
      const data = streamed ? unchunk(body) : body;
      this.objects.set(key, data);
      this.puts.push({ key, bytes: data.length, at: Date.now() });
      return new Response(null, { status: 200, headers: { etag: `"${createHash("md5").update(data).digest("hex")}"` } });
    }
    if ((req.method === "GET" || req.method === "HEAD") && key) {
      const o = this.objects.get(key);
      if (!o) return new Response(req.method === "HEAD" ? null : "<Error><Code>NoSuchKey</Code></Error>", { status: 404 });
      return new Response(req.method === "HEAD" ? null : o, { status: 200, headers: { "content-length": String(o.length) } });
    }
    return new Response("<Error><Code>NotImplemented</Code></Error>", { status: 501 });
  }

  /** Null when the request is signed with this bucket's key, else why not. */
  private verify(req: Request, url: URL, body: Uint8Array): string | null {
    const auth = req.headers.get("authorization") ?? "";
    const m = /^AWS4-HMAC-SHA256 Credential=([^/]+)\/(\d{8})\/([^/]+)\/s3\/aws4_request, ?SignedHeaders=([^,]+), ?Signature=([0-9a-f]{64})$/.exec(auth);
    if (!m) return "no AWS4-HMAC-SHA256 authorization";
    const [, akid, date, region, signed, signature] = m as unknown as string[];
    if (akid !== this.accessKey) return "unknown access key";
    if (region !== this.region) return `wrong region ${region}`;
    const amzDate = req.headers.get("x-amz-date") ?? "";
    if (!amzDate.startsWith(date!)) return "x-amz-date does not match the credential date";
    const names = signed!.split(";");
    if (!names.includes("host") || !names.includes("x-amz-date")) return "host and x-amz-date must be signed";
    const payload = req.headers.get("x-amz-content-sha256") ?? "";
    const actual = createHash("sha256").update(body).digest("hex");
    // The SDKs' aws-chunked uploads sign the literal STREAMING-… marker as the payload hash.
    const marker = payload === "UNSIGNED-PAYLOAD" || payload.startsWith("STREAMING-");
    if (!marker && payload !== actual) return "x-amz-content-sha256 does not match the body";
    const query = [...url.searchParams.entries()]
      .map(([k, v]) => [enc(k), enc(v)] as const)
      .sort(([a, x], [b, y]) => (a === b ? (x < y ? -1 : 1) : a < b ? -1 : 1))
      .map(([k, v]) => `${k}=${v}`)
      .join("&");
    const headers = names.map((n) => `${n}:${(req.headers.get(n) ?? "").trim().replace(/\s+/g, " ")}\n`).join("");
    const canonical = [req.method, url.pathname, query, headers, signed, payload].join("\n");
    const scope = `${date}/${region}/s3/aws4_request`;
    const toSign = ["AWS4-HMAC-SHA256", amzDate, scope, createHash("sha256").update(canonical).digest("hex")].join("\n");
    let k: Buffer = createHmac("sha256", `AWS4${this.secretKey}`).update(date!).digest();
    for (const part of [region!, "s3", "aws4_request"]) k = createHmac("sha256", k).update(part).digest();
    const expected = createHmac("sha256", k).update(toSign).digest("hex");
    return expected === signature ? null : "signature does not match";
  }
}

/** The data of an aws-chunked body: `<hex size>[;chunk-signature=…]\r\n<data>\r\n` until a zero-size chunk. */
function unchunk(body: Uint8Array): Uint8Array {
  const out: number[] = [];
  let i = 0;
  for (;;) {
    let end = i;
    while (end + 1 < body.length && !(body[end] === 13 && body[end + 1] === 10)) end++;
    const size = parseInt(new TextDecoder().decode(body.subarray(i, end)).split(";")[0]!, 16);
    if (!size) break;
    const start = end + 2;
    for (let j = start; j < start + size; j++) out.push(body[j]!);
    i = start + size + 2;
  }
  return Uint8Array.from(out);
}

const enc = (s: string) => encodeURIComponent(s).replace(/[!'()*]/g, (c) => `%${c.charCodeAt(0).toString(16).toUpperCase()}`);
