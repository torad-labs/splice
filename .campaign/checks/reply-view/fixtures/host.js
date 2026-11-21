// The checks' host: it imports the reader as any host does and hands it to the test, which mounts it and feeds it.
import { mount } from "/reply-view/reply-view.js";

window.RV = { mount, view: null, host: document.getElementById("host") };
window.RV_READY = true;
