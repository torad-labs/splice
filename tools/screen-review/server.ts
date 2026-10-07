// NEW: loopback-only, daemon-free Bun server for local mock review and private JSON feedback.
import { join, resolve } from "node:path";
import { discover, parseMutation, ReviewStore } from "./store.ts";

export interface ReviewOptions {
  screensRoot: string;
  feedbackFile: string;
  port?: number;
}
const shellCsp = "default-src 'none'; script-src 'self'; style-src 'self'; frame-src 'self'; connect-src 'self'; img-src 'self' data:; frame-ancestors 'none'; base-uri 'none'";
const mockCsp = "default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; img-src data:; font-src data:; base-uri 'none'";
const sizeBridge = `<script>
(() => {
  let last = 0;
  const measure = () => {
    const height = Math.max(document.body.scrollHeight, document.documentElement.scrollHeight);
    if (height !== last) {
      last = height;
      parent.postMessage({ type: "screen-review:size", height }, "*");
    }
  };
  new ResizeObserver(measure).observe(document.body);
  addEventListener("load", measure);
  document.fonts.ready.then(measure);
  measure();
})();
</script>`;
function response(body: BodyInit | null, status = 200, type = "application/json", csp = shellCsp): Response {
  return new Response(body, { status, headers: {
    "Content-Type": type,
    "Cache-Control": "no-store",
    "Content-Security-Policy": csp,
    "X-Content-Type-Options": "nosniff",
    "Referrer-Policy": "no-referrer",
  } });
}
export async function startReview(options: ReviewOptions) {
  const store = new ReviewStore(options.feedbackFile);
  const built = await Bun.build({ entrypoints: [join(import.meta.dir, "ui.ts")], target: "browser", format: "esm", minify: false });
  if (!built.success) throw new Error("The review interface could not be built.");
  const javascript = await built.outputs[0]!.text();
  const server = Bun.serve({
    hostname: "127.0.0.1",
    port: options.port ?? 4375,
    maxRequestBodySize: 64 * 1024,
    async fetch(request) {
      const url = new URL(request.url);
      if (url.origin !== server.url.origin) return response(JSON.stringify({ error: "Loopback origin only." }), 403);
      try {
        if (request.method === "POST" && url.pathname === "/api/feedback") {
          if (request.headers.get("origin") !== server.url.origin ||
              request.headers.get("content-type")?.split(";")[0] !== "application/json") {
            return response(JSON.stringify({ error: "Feedback must come from this local review page." }), 403);
          }
          const mutation = parseMutation(await request.json());
          if (!discover(options.screensRoot).some((screen) => screen.id === mutation.screen && screen.ready)) {
            return response(JSON.stringify({ error: "That screen is no longer available. Reload the screen list." }), 404);
          }
          return response(JSON.stringify({ at: store.apply(mutation) }));
        }
        if (request.method !== "GET") return response(JSON.stringify({ error: "Method not allowed." }), 405);
        if (url.pathname === "/api/screens") return response(JSON.stringify(discover(options.screensRoot)));
        if (url.pathname === "/api/feedback") return response(JSON.stringify(store.read()));
        if (url.pathname === "/app.js") return response(javascript, 200, "text/javascript");
        if (url.pathname === "/style.css") return response(Bun.file(join(import.meta.dir, "style.css")), 200, "text/css");
        if (url.pathname === "/") return response(Bun.file(join(import.meta.dir, "index.html")), 200, "text/html");
        const match = /^\/screens\/([^/]+)\/index\.html$/.exec(url.pathname);
        const id = match && decodeURIComponent(match[1]!);
        if (id && discover(options.screensRoot).some((screen) => screen.id === id && screen.ready)) {
          const html = await Bun.file(join(options.screensRoot, id, "index.html")).text();
          return response(html + sizeBridge, 200, "text/html", mockCsp);
        }
        return response(JSON.stringify({ error: "Not found." }), 404);
      } catch (error) {
        const message = error instanceof Error ? error.message : "The review action failed.";
        return response(JSON.stringify({ error: message }), 400);
      }
    },
  });
  return server;
}

if (import.meta.main) {
  if (process.argv.length > 2) throw new Error("Usage: bun tools/screen-review/server.ts");
  const local = resolve(import.meta.dir, "../../captures/screen-review");
  const screensRoot = join(local, "screens");
  const server = await startReview({ screensRoot, feedbackFile: join(local, "feedback.json") });
  console.log(`Screen review: ${server.url}\nFeedback stays in captures/screen-review/feedback.json`);
  for (const signal of ["SIGINT", "SIGTERM"] as const) process.once(signal, () => { server.stop(true); process.exit(0); });
}
