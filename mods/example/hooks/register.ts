// An example mod written against the contract. Installed today, its served event handlers (session.start) run as
// observers. Its tool.call handlers load but never fire, because no session is offered the mod's own tools yet, and
// its sessions.list and ui.render handlers are refused at load (mods/README.md, "What runs today").
// It shows the three shapes together: a lifecycle hook (session.start), rows with actions
// (sessions.list and the resolve tool behind its buttons) and a page (ui.render).
import type { Json, ModSessionRow, Register, UiRenderAnswer } from "../../contract/index.ts";

interface Review {
  readonly id: string;
  readonly session: string;
  readonly title: string;
}

const MOD = "reviews";

function isReview(v: Json | undefined): v is Json & { id: string; session: string; title: string } {
  return typeof v === "object" && v !== null && !Array.isArray(v) &&
    typeof (v as { id?: unknown }).id === "string" &&
    typeof (v as { session?: unknown }).session === "string" &&
    typeof (v as { title?: unknown }).title === "string";
}

function row(r: Review): ModSessionRow {
  return {
    id: `${MOD}:${r.id}`,
    kind: "review",
    parent: r.session,
    title: r.title,
    status: "waiting",
    needs: { text: "Approve the change or say what to redo." },
    actions: [
      { label: "Approve", tool: "resolve", arguments: { review: r.id, verdict: "approved" }, confirm: true },
      {
        label: "Ask for changes",
        tool: "resolve",
        arguments: { review: r.id, verdict: "changes" },
        input: { name: "reason", label: "What should change?", multiline: true },
      },
    ],
    mod: MOD,
  };
}

function page(reviews: readonly Review[], theme: "light" | "dark"): UiRenderAnswer {
  const fg = theme === "dark" ? "#e8e8e8" : "#1a1a1a";
  const items = reviews.map((r) => `<li>${r.title.replace(/[&<>"]/g, (c) => `&#${c.charCodeAt(0)};`)}</li>`).join("");
  return {
    title: "Review queue",
    html: `<!doctype html><html><head><meta charset="utf-8"><style>body{font:14px system-ui;color:${fg};margin:16px}</style></head>` +
      `<body><h1>${reviews.length} open</h1><ul>${items}</ul></body></html>`,
  };
}

export const register: Register = (on) => {
  const open = async (store: { list(p?: string): Promise<readonly string[]>; get(k: string): Promise<Json | undefined> }) => {
    const reviews: Review[] = [];
    for (const key of await store.list("review/")) {
      const v = await store.get(key);
      if (isReview(v)) reviews.push(v);
    }
    return reviews;
  };

  // A lifecycle hook: a session launched with SPLICE_LABEL_REVIEW=<title> opens a review under it.
  on("session.start", async ($, e, next) => {
    const title = e.labels["review"];
    if (title && !e.seenBefore) {
      await $.store.set(`review/${e.session}`, { id: e.session, session: e.session, title });
      $.sessions.invalidate();
    }
    return next(e);
  });

  // Rows: the onion adds this mod's rows to what the rest of the chain answers.
  on("sessions.list", async ($, e, next) => {
    const rows = await next(e);
    if (!e.allRows) return rows;
    return [...rows, ...(await open($.store)).map(row)];
  }).catch(async ($, e, next) => {
    $.log(`sessions.list: ${e.error.message}`);
    return next(e);
  });

  // The tool behind the row's buttons. It is marked for the app alone, so the model never sees it.
  on("tool.call", { server: MOD, tool: "resolve" }, async ($, e, next) => {
    if (e.caller !== "app") return next(e);
    const review = String(e.arguments["review"]);
    const verdict = String(e.arguments["verdict"]);
    await $.store.delete(`review/${review}`);
    $.sessions.invalidate();
    const reason = typeof e.arguments["reason"] === "string" ? `: ${e.arguments["reason"]}` : "";
    return { content: [{ type: "text", text: `Review ${review} closed as ${verdict}${reason}.` }] };
  });

  // A tool the model calls, answering in words and a declared view.
  on("tool.call", { server: MOD, tool: "board" }, async ($) => {
    const reviews = await open($.store);
    return $.ui.respond({ text: `${reviews.length} review(s) open.`, ui: "ui://reviews/board" });
  });

  // The page, and the view the board tool names.
  on("ui.render", { slot: "page", page: "queue" }, async ($, e) => page(await open($.store), e.theme));
  on("ui.render", { slot: "view" }, async ($, e, next) =>
    e.slot.kind === "view" && e.slot.uri === "ui://reviews/board" ? page(await open($.store), e.theme) : next(e));
};
