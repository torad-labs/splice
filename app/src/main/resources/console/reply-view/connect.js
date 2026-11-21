// The reply view's connector for splice's own pages: it reads one session from the splice that served the page and hands
// a view what it reads, drawing nothing itself. A host that relays the transcript and the feed through its own channel
// does not need this file; it feeds the view (reply-view.js) itself.
//
//   import { connect } from "/reply-view/connect.js";
//   const conn = connect(view, { session, onLocked, onEarlier });   // view: anything with addHistory and addLive
//   await conn.earlier();                                           // the page before the oldest one handed on
//   conn.close();                                                   // stops every read
//
// History: GET /api/sessions/{id}/transcript?before=end first, then cursor=after:<highest index handed on> soon after
// each turn starts or ends and each hello (and every 5 s while the feed is not connected). The feed:
// GET /api/sessions/{id}/live read with fetch and a stream reader, because an EventSource cannot send the key in a
// header and the key never goes in an address. The key is the console key this browser holds (console/api.js).

const KEY_STORE = "splice-console-access-key";
/** After a turn starts or ends, the transcript read waits this long, so what the turn waited on or wrote is there. */
const SOON_MS = 300;
/** How often history is read while no feed is connected. */
const POLL_MS = 5_000;
/** A feed that dropped is joined again after this, doubling while joins keep failing, up to FEED_MAX_MS. */
const FEED_FIRST_MS = 1_000;
const FEED_MAX_MS = 30_000;
/** A splice that refuses the feed (a 404, a 5xx) is asked again after this. */
const FEED_REFUSED_MS = 30_000;

export function connect(view, { session, onLocked, onEarlier } = {}) {
  const base = `/api/sessions/${encodeURIComponent(session)}`;
  const aborts = new Set();
  const timers = new Set();
  /** What was handed on, by index, as it was sent: an `after:` read sends the last message again, unchanged or grown. */
  const handed = new Map();
  let closed = false;
  let locked = false;
  let earliest = null; // the cursor of the page before the oldest one handed on, or null when none is older
  let highest = -1;
  let feedOpen = false;
  let feedDelay = FEED_FIRST_MS;
  let reading = null; // the history read running now
  let again = false; // one more read was asked for while it ran
  let pollTimer = null;
  let soonTimer = null;

  const later = (ms, fn) => {
    const t = setTimeout(() => {
      timers.delete(t);
      if (!closed && !locked) fn();
    }, ms);
    timers.add(t);
    return t;
  };
  const drop = (t) => {
    if (t === null) return;
    clearTimeout(t);
    timers.delete(t);
  };

  const key = () => {
    try {
      return localStorage.getItem(KEY_STORE) || "";
    } catch {
      return "";
    }
  };

  const lock = () => {
    if (locked || closed) return;
    locked = true;
    stopAll();
    if (onLocked) onLocked();
  };

  const stopAll = () => {
    for (const a of aborts) a.abort();
    aborts.clear();
    for (const t of timers) clearTimeout(t);
    timers.clear();
    pollTimer = null;
    soonTimer = null;
  };

  /** One request with the key; a 401 locks the connector. Resolves to the response, or null when it was refused. */
  const get = async (path, accept, abort = new AbortController()) => {
    aborts.add(abort);
    try {
      const headers = { Accept: accept };
      const k = key();
      if (k) headers.Authorization = `Bearer ${k}`;
      const res = await fetch(path, { headers, signal: abort.signal, cache: "no-store" });
      if (res.status === 401) {
        lock();
        return null;
      }
      return res;
    } finally {
      if (accept !== "text/event-stream") aborts.delete(abort);
    }
  };

  /** Hand the view the messages it has not been handed as they are now. */
  const hand = (messages) => {
    if (closed || locked || !Array.isArray(messages)) return;
    const fresh = [];
    for (const m of messages) {
      if (!m || typeof m.index !== "number") continue;
      const sent = JSON.stringify(m);
      if (handed.get(m.index) === sent) continue;
      handed.set(m.index, sent);
      if (m.index > highest) highest = m.index;
      fresh.push(m);
    }
    if (fresh.length) view.addHistory(fresh);
  };

  const transcript = async (query) => {
    const res = await get(`${base}/transcript?${query}`, "application/json");
    if (!res || !res.ok) return null;
    try {
      return await res.json();
    } catch {
      return null;
    }
  };

  const tellEarlier = () => {
    if (onEarlier && !closed && !locked) onEarlier(earliest !== null);
  };

  /** The newest page, as a session opens: the page before it is remembered for earlier(). */
  const newest = async () => {
    const page = await transcript("before=end");
    if (!page || closed || locked) return;
    hand(page.messages);
    earliest = typeof page.earlier === "string" && page.earlier ? page.earlier : null;
    tellEarlier();
  };

  /** What follows the highest index handed on; a page that says more is waiting reads the newest page instead. */
  const follow = async () => {
    if (highest < 0) return newest();
    const page = await transcript(`cursor=${encodeURIComponent(`after:${highest}`)}`);
    if (!page || closed || locked) return;
    if (page.more === true) return newest();
    hand(page.messages);
  };

  /** Reads never overlap: one asked for while one runs is run once after it. */
  const read = (what) => {
    if (closed || locked) return;
    if (reading) {
      again = true;
      return;
    }
    reading = (async () => {
      try {
        await what();
      } catch {
        /* a failed read is tried again by the next turn, end, hello or poll */
      }
      reading = null;
      if (again && !closed && !locked) {
        again = false;
        read(follow);
      }
    })();
  };

  const readSoon = () => {
    drop(soonTimer);
    soonTimer = later(SOON_MS, () => {
      soonTimer = null;
      read(follow);
    });
  };

  const poll = () => {
    drop(pollTimer);
    pollTimer = null;
    if (feedOpen) return;
    pollTimer = later(POLL_MS, () => {
      pollTimer = null;
      read(follow);
      poll();
    });
  };

  /** One SSE block ("event: x\ndata: {...}"), handed on when it parses; comments and anything unparseable are dropped. */
  const block = (lines) => {
    let event = "message";
    const data = [];
    for (const line of lines) {
      if (line === "" || line.startsWith(":")) continue;
      const colon = line.indexOf(":");
      const field = colon < 0 ? line : line.slice(0, colon);
      let value = colon < 0 ? "" : line.slice(colon + 1);
      if (value.startsWith(" ")) value = value.slice(1);
      if (field === "event") event = value;
      else if (field === "data") data.push(value);
    }
    if (!data.length) return;
    let parsed;
    try {
      parsed = JSON.parse(data.join("\n"));
    } catch {
      return;
    }
    if (closed || locked) return;
    view.addLive({ event, data: parsed });
    if (event === "turn" || event === "end" || event === "hello") readSoon();
  };

  const feed = async () => {
    if (closed || locked) return;
    let opened = false;
    const abort = new AbortController();
    try {
      const res = await get(`${base}/live`, "text/event-stream", abort);
      if (!res) return;
      if (!res.ok || !res.body) {
        // not a lock: this splice serves no feed now. History is polled, and the feed is asked again later.
        aborts.delete(abort);
        poll();
        later(FEED_REFUSED_MS, feed);
        return;
      }
      opened = true;
      feedOpen = true;
      feedDelay = FEED_FIRST_MS;
      drop(pollTimer);
      pollTimer = null;
      const reader = res.body.getReader();
      const decoder = new TextDecoder();
      let buffer = "";
      let lines = [];
      for (;;) {
        const { value, done } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });
        // a line ends at CRLF, LF or CR; a CR at the very end may be half of a CRLF still on its way
        let at;
        while ((at = buffer.search(/\r\n|\n|\r(?!$)/)) >= 0) {
          const line = buffer.slice(0, at);
          buffer = buffer.slice(at + (buffer.startsWith("\r\n", at) ? 2 : 1));
          if (line === "") {
            block(lines);
            lines = [];
          } else {
            lines.push(line);
          }
          if (closed || locked) return;
        }
      }
    } catch {
      /* the stream failed or was aborted: joined again below unless the connector stopped */
    }
    aborts.delete(abort);
    if (closed || locked) return;
    feedOpen = false;
    poll();
    const wait = opened ? FEED_FIRST_MS : feedDelay;
    feedDelay = opened ? FEED_FIRST_MS * 2 : Math.min(feedDelay * 2, FEED_MAX_MS);
    later(wait, feed);
  };

  read(newest);
  feed();

  return {
    /** Read the page before the oldest one handed on, hand it on, and say whether older ones remain. */
    async earlier() {
      if (closed || locked || earliest === null) return;
      if (reading) await reading;
      if (closed || locked || earliest === null) return;
      const page = await transcript(`before=${encodeURIComponent(earliest)}`);
      if (!page || closed || locked) return;
      hand(page.messages);
      earliest = typeof page.earlier === "string" && page.earlier ? page.earlier : null;
      tellEarlier();
    },
    close() {
      closed = true;
      stopAll();
    },
  };
}
