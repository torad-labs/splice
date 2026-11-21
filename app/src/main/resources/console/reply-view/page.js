// The reader's own page: /reply-view/index.html?session=<id> shows one session through the reader (reply-view.js) and
// its connector (connect.js); with no session it lists the sessions to open. The page owns its scroll: it opens at the
// newest message, follows new ones while the person is at the bottom, stops when they scroll up, and reads older pages
// when they reach the top, keeping the line they were reading where it was.
import { mount } from "/reply-view/reply-view.js";
import { connect } from "/reply-view/connect.js";

const KEY_STORE = "splice-console-access-key";
const THEME_STORE = "splice-console-theme";
/** Within this many pixels of the bottom counts as at the bottom; of the top, as asking for older messages. */
const NEAR_PX = 48;
const LOCKED = "This browser is not signed in to splice. Run ";

// the console's saved theme, as the console's pages apply it
try {
  const saved = localStorage.getItem(THEME_STORE);
  document.documentElement.dataset.theme = saved === "day" ? "day" : "night";
} catch {
  document.documentElement.dataset.theme = "night";
}

const $ = (id) => document.getElementById(id);
const session = new URLSearchParams(location.search).get("session");

function key() {
  try {
    return localStorage.getItem(KEY_STORE) || "";
  } catch {
    return "";
  }
}

/** The page says only how to sign in. */
function lock() {
  if (document.body.dataset.locked) return;
  document.body.dataset.locked = "1";
  const p = document.createElement("p");
  p.className = "locked";
  const code = document.createElement("code");
  code.textContent = "splice console";
  p.append(LOCKED, code, " in a terminal, then reload.");
  document.body.replaceChildren(p);
  document.title = "Not signed in · splice";
}

/** The sessions splice lists, or null when it refused (a refusal for want of a key locks the page). */
async function sessions() {
  const headers = { Accept: "application/json" };
  if (key()) headers.Authorization = `Bearer ${key()}`;
  try {
    const res = await fetch("/api/sessions", { headers, cache: "no-store" });
    if (res.status === 401) {
      lock();
      return null;
    }
    if (!res.ok) return null;
    const body = await res.json();
    return Array.isArray(body?.sessions) ? body.sessions : [];
  } catch {
    return null;
  }
}

const nameOf = (s) => s.name || s.title || String(s.session_id ?? "").slice(0, 8);
const projectOf = (s) => s.project?.name || (s.cwd ? String(s.cwd).split("/").filter(Boolean).pop() : "") || "";
const when = (s) => Date.parse(s.updated_at ?? "") || s.last?.ts || 0;

async function picker() {
  document.body.classList.add("is-picker");
  const list = $("picker");
  list.hidden = false;
  $("name").textContent = "Read a session";
  $("meta").textContent = "Choose one to open its conversation";
  const rows = await sessions();
  if (rows === null) {
    if (!document.body.dataset.locked) $("meta").textContent = "splice did not answer; reload to try again";
    return;
  }
  if (!rows.length) {
    $("meta").textContent = "No sessions yet";
    return;
  }
  for (const s of [...rows].sort((a, b) => when(b) - when(a))) {
    const li = document.createElement("li");
    const a = document.createElement("a");
    a.href = `?session=${encodeURIComponent(s.session_id)}`;
    const name = document.createElement("span");
    name.className = "pick-name";
    name.textContent = nameOf(s);
    const meta = document.createElement("span");
    meta.className = "pick-meta";
    meta.textContent = [projectOf(s), s.status].filter(Boolean).join(" · ");
    a.append(name, meta);
    li.append(a);
    list.append(li);
  }
}

async function reader(id) {
  const scroller = document.scrollingElement || document.documentElement;
  const edge = $("edge");
  const jump = $("jump");
  let pinned = true;
  let opened = false;
  let more = false;
  let loading = false;

  // where the page was last scrolled to, by the person or by the page: only a move up from there unpins it, so the
  // page growing under a pinned reader (fonts loading, a reply arriving, a fold opening) never counts as leaving
  let lastTop = 0;

  const atBottom = () => scroller.scrollHeight - scroller.scrollTop - scroller.clientHeight <= NEAR_PX;
  const toBottom = () => {
    scroller.scrollTop = scroller.scrollHeight;
    lastTop = scroller.scrollTop;
  };
  const showJump = () => {
    jump.hidden = pinned;
  };

  const view = mount($("reader"), {});
  $("reader").addEventListener("replyview:change", () => {
    if (!opened) opened = true;
    if (pinned) toBottom();
  });
  // whatever makes the page taller while it is pinned (the console's fonts arriving after the first draw included),
  // it stays at the newest line
  new ResizeObserver(() => {
    if (opened && pinned) toBottom();
  }).observe($("column"));

  const earlier = async () => {
    if (!more || loading) return;
    loading = true;
    const before = scroller.scrollHeight;
    const top = scroller.scrollTop;
    try {
      await conn.earlier();
    } finally {
      // keep the line the person was reading where it was
      scroller.scrollTop = top + (scroller.scrollHeight - before);
      lastTop = scroller.scrollTop;
      loading = false;
    }
  };

  const conn = connect(view, {
    session: id,
    onLocked: lock,
    onEarlier: (left) => {
      more = left;
      edge.hidden = false;
      edge.textContent = left ? "Loading earlier messages…" : "Start of the conversation";
      if (left && scroller.scrollTop <= NEAR_PX && opened && scroller.scrollHeight > scroller.clientHeight) earlier();
    },
  });

  addEventListener(
    "scroll",
    () => {
      const top = scroller.scrollTop;
      if (atBottom()) pinned = true;
      else if (top < lastTop - 1) pinned = false; // the person scrolled up
      lastTop = top;
      showJump();
      if (scroller.scrollTop <= NEAR_PX) earlier();
    },
    { passive: true },
  );
  jump.addEventListener("click", () => {
    pinned = true;
    toBottom();
    showJump();
  });
  addEventListener("pagehide", () => conn.close());

  // the header names the session
  const rows = await sessions();
  if (document.body.dataset.locked) return;
  const row = rows?.find((s) => s.session_id === id);
  const name = row ? nameOf(row) : id.slice(0, 8);
  $("name").textContent = name;
  $("meta").textContent = row ? [projectOf(row), row.head].filter(Boolean).join(" · ") : "";
  document.title = `${name} · splice`;
}

if (session) reader(session);
else picker();
