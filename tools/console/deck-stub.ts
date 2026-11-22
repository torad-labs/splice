// What the Night Deck pages' tests share: a stub daemon that serves the console folder by the names ConsoleMount serves
// and answers /api/* from made-up rows a test hands it (a value, or a function of the request), recording every call;
// and Chromium. Every value a test gives it is made up.
import { chromium, type Browser } from "@playwright/test";
import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { layout } from "../gate/src/lib/repo.ts";

export const CONSOLE = join(layout().repoRoot, "app/src/main/resources/console");
const ASSET = /^((fonts|brand)\/)?[a-z0-9][a-z0-9-]*\.(html|css|js|woff2|svg|png)$/;
const READER = /^reply-view\/[a-z0-9][a-z0-9-]*\.(html|css|js)$/;
const TYPES: Record<string, string> = {
  html: "text/html; charset=utf-8", css: "text/css", js: "text/javascript", woff2: "font/woff2", svg: "image/svg+xml", png: "image/png",
};

export type Answer = unknown | ((url: URL, body: unknown, req: Request) => unknown);
export interface Call { method: string; path: string; query: URLSearchParams; body: unknown }
export interface Stub { url: string; calls: Call[]; answers: Record<string, Answer>; stop(): void }

/** [answers] by exact path ("METHOD /path" wins over "/path"); any other /api/* path answers {}. */
export function stub(answers: Record<string, Answer>): Stub {
  const calls: Call[] = [];
  const server = Bun.serve({
    port: 0,
    hostname: "127.0.0.1",
    async fetch(req) {
      const url = new URL(req.url), path = url.pathname;
      if (path.startsWith("/api/")) {
        const text = req.method === "GET" || req.method === "HEAD" ? "" : await req.text();
        let body: unknown = text;
        try { body = text ? JSON.parse(text) : null; } catch { /* kept as text */ }
        calls.push({ method: req.method, path, query: url.searchParams, body });
        const a = s.answers[`${req.method} ${path}`] ?? s.answers[path];
        const out = typeof a === "function" ? await (a as (u: URL, b: unknown, r: Request) => unknown)(url, body, req) : a;
        return out instanceof Response ? out : Response.json(out ?? {});
      }
      const name = path.slice(1), file = join(CONSOLE, name);
      if (!(ASSET.test(name) || READER.test(name)) || !existsSync(file)) return new Response("", { status: 404 });
      return new Response(readFileSync(file), { headers: { "content-type": TYPES[name.split(".").pop()!]! } });
    },
  });
  const s: Stub = { url: `http://127.0.0.1:${server.port}`, calls, answers, stop: () => server.stop(true) };
  return s;
}

export const launch = (): Promise<Browser> =>
  chromium.launch({ executablePath: process.env.SCREEN_REVIEW_BROWSER || undefined, args: ["--no-sandbox"] });
