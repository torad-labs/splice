// The connector against a view that only records what it is handed, so a check reads what the connector read and
// passed on: transcript messages, feed events, and whether it found this browser locked out or older messages left.
import { connect } from "/reply-view/connect.js";

const calls = [];
const view = {
  addHistory: (messages) => calls.push({ kind: "history", messages: JSON.parse(JSON.stringify(messages)) }),
  addLive: (events) => calls.push({ kind: "live", events: JSON.parse(JSON.stringify(Array.isArray(events) ? events : [events])) }),
};
const session = new URLSearchParams(location.search).get("session") || "aaaa1111";
window.CALLS = calls;
window.LOCKED = 0;
window.EARLIER = [];
window.CONN = connect(view, {
  session,
  onLocked: () => { window.LOCKED += 1; },
  onEarlier: (more) => { window.EARLIER.push(more); },
});
window.CONN_READY = true;
