/*
 * MajorDome chat UI. No build step, no inline scripts (CSP-friendly).
 * Mounts either as the full page (/majordome) or as a floating panel on every other page.
 */
(function () {
  "use strict";

  if (window.__majordomeLoaded) {
    return;
  }
  window.__majordomeLoaded = true;

  const pageRoot = document.getElementById("majordome-page-root");
  const root = pageRoot || document.getElementById("majordome-floating-root");
  if (!root) {
    return;
  }
  const floatingRoot = document.getElementById("majordome-floating-root");
  if (pageRoot && floatingRoot) {
    floatingRoot.remove(); // the full-page chat replaces the floating one
  }
  const endpoint = root.dataset.endpoint;
  const floating = root.dataset.mode === "floating";
  const OPEN_KEY = "majordome.open";

  // ---------- helpers ----------

  function el(tag, cls, text) {
    const e = document.createElement(tag);
    if (cls) {
      e.className = cls;
    }
    if (text !== undefined && text !== null) {
      e.textContent = text;
    }
    return e;
  }

  // Plain ES2015 + Promises (no async/await): HtmlUnit, used by Jenkins tests, must parse this file.

  function crumbHeaders() {
    const headers = {};
    const h = document.head ? document.head.dataset : {};
    if (h.crumbHeader && h.crumbValue) {
      headers[h.crumbHeader] = h.crumbValue;
    } else if (window.crumb && window.crumb.fieldName) {
      headers[window.crumb.fieldName] = window.crumb.value;
    }
    return headers;
  }

  function call(path, body) {
    const opts = body === undefined
      ? { method: "GET", credentials: "same-origin" }
      : {
          method: "POST",
          credentials: "same-origin",
          headers: Object.assign({ "Content-Type": "application/json" }, crumbHeaders()),
          body: JSON.stringify(body),
        };
    return fetch(endpoint + "/" + path, opts).then(function (rsp) {
      return rsp.json()
        .catch(function () {
          return { error: "Unexpected response (HTTP " + rsp.status + ")" };
        })
        .then(function (data) {
          if (!rsp.ok) {
            throw new Error(data.error || "HTTP " + rsp.status);
          }
          return data;
        });
    });
  }

  function storageGet(key) {
    try {
      return window.sessionStorage.getItem(key);
    } catch (e) {
      return null;
    }
  }

  function storageSet(key, value) {
    try {
      window.sessionStorage.setItem(key, value);
    } catch (e) {
      // storage unavailable: the panel just starts closed
    }
  }

  /** Minimal, safe Markdown: everything is escaped first, then a few constructs are formatted. */
  function renderMarkdown(text) {
    const escape = (s) => s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
    const parts = String(text).split(/```/);
    let html = "";
    parts.forEach((part, i) => {
      if (i % 2 === 1) {
        const code = part.replace(/^[a-zA-Z0-9_-]*\n/, "");
        html += "<pre><code>" + escape(code) + "</code></pre>";
        return;
      }
      let s = escape(part);
      s = s.replace(/`([^`\n]+)`/g, "<code>$1</code>");
      s = s.replace(/\*\*([^*\n]+)\*\*/g, "<strong>$1</strong>");
      s = s.replace(/(^|[\s(])_([^_\n]+)_(?=[\s).,!?]|$)/g, "$1<em>$2</em>");
      s = s.replace(/^#{1,6} (.*)$/gm, "<strong>$1</strong>");
      s = s.replace(/^\s*[-*] (.*)$/gm, "• $1");
      s = s.replace(/\n/g, "<br>");
      html += s;
    });
    return html;
  }

  // ---------- layout ----------

  const panel = el("div", "majordome-panel" + (floating ? " majordome-panel--floating" : ""));
  const header = el("div", "majordome-header");
  header.appendChild(el("span", "majordome-title", "MajorDome"));
  const resetBtn = el("button", "majordome-icon-btn", "New chat");
  resetBtn.type = "button";
  resetBtn.title = "Start a new conversation";
  header.appendChild(resetBtn);
  if (floating) {
    const closeBtn = el("button", "majordome-icon-btn", "✕");
    closeBtn.type = "button";
    closeBtn.title = "Close";
    closeBtn.setAttribute("aria-label", "Close");
    closeBtn.addEventListener("click", () => setOpen(false));
    header.appendChild(closeBtn);
  }
  const log = el("div", "majordome-log");
  log.setAttribute("aria-live", "polite");
  const form = el("form", "majordome-form");
  const input = el("textarea", "majordome-input");
  input.rows = 2;
  input.placeholder = "Ask about jobs, builds, failures… (Enter to send, Shift+Enter for a new line)";
  const sendBtn = el("button", "jenkins-button jenkins-button--primary majordome-send", "Send");
  sendBtn.type = "submit";
  form.appendChild(input);
  form.appendChild(sendBtn);
  panel.appendChild(header);
  panel.appendChild(log);
  panel.appendChild(form);

  let launcher = null;
  if (floating) {
    launcher = el("button", "majordome-launcher", "AI");
    launcher.type = "button";
    launcher.title = "Open MajorDome, the AI assistant";
    launcher.setAttribute("aria-label", "Open MajorDome, the AI assistant");
    launcher.addEventListener("click", () => setOpen(true));
    root.appendChild(launcher);
  }
  root.appendChild(panel);

  function setOpen(open) {
    if (!floating) {
      return;
    }
    panel.hidden = !open;
    launcher.hidden = open;
    storageSet(OPEN_KEY, open ? "1" : "0");
    if (open) {
      input.focus();
      log.scrollTop = log.scrollHeight;
    }
  }

  // ---------- rendering ----------

  function scroll() {
    log.scrollTop = log.scrollHeight;
  }

  function addMessage(kind, text) {
    const m = el("div", "majordome-msg majordome-msg--" + kind);
    if (kind === "assistant") {
      m.innerHTML = renderMarkdown(text); // escaped inside renderMarkdown
    } else {
      m.textContent = text;
    }
    log.appendChild(m);
    scroll();
    return m;
  }

  const STATUS_LABEL = {
    PENDING: "Waiting for your confirmation",
    EXECUTED: "Executed",
    FAILED: "Failed",
    REJECTED: "Rejected",
    EXPIRED: "Expired",
  };

  function addProposal(p) {
    const card = el("div", "majordome-proposal");
    card.appendChild(el("div", "majordome-proposal-label", "Proposed action · " + p.tool));
    card.appendChild(el("div", "majordome-proposal-text", p.text));
    const status = el("div", "majordome-proposal-status");
    const buttons = el("div", "majordome-proposal-buttons");
    const confirmBtn = el("button", "jenkins-button jenkins-button--primary", "Confirm");
    const rejectBtn = el("button", "jenkins-button", "Reject");
    confirmBtn.type = "button";
    rejectBtn.type = "button";
    buttons.appendChild(confirmBtn);
    buttons.appendChild(rejectBtn);
    card.appendChild(buttons);
    card.appendChild(status);

    function show(state, result) {
      card.dataset.status = state;
      status.textContent = STATUS_LABEL[state] + (result ? ": " + result : "");
      buttons.hidden = state !== "PENDING";
    }
    show(p.status || "PENDING", p.result);

    function decide(path) {
      confirmBtn.disabled = true;
      rejectBtn.disabled = true;
      call(path, { id: p.id })
        .then(function (r) {
          show(r.status, r.message);
        })
        .catch(function (e) {
          show(/expired/i.test(e.message) ? "EXPIRED" : "FAILED", e.message);
        });
    }
    confirmBtn.addEventListener("click", () => decide("confirm"));
    rejectBtn.addEventListener("click", () => decide("reject"));

    log.appendChild(card);
    scroll();
  }

  function renderEntry(e) {
    switch (e.kind) {
      case "USER":
        addMessage("user", e.text);
        break;
      case "ASSISTANT":
        addMessage("assistant", e.text);
        break;
      case "ERROR":
        addMessage("error", e.text);
        break;
      case "PROPOSAL":
        addProposal({ id: e.id, tool: e.tool, text: e.text, status: e.status, result: e.result });
        break;
      default:
        break;
    }
  }

  // ---------- behaviour ----------

  let busy = false;

  function send() {
    const text = input.value.trim();
    if (!text || busy) {
      return;
    }
    busy = true;
    sendBtn.disabled = true;
    input.value = "";
    addMessage("user", text);
    const thinking = addMessage("thinking", "Thinking…");
    function done() {
      busy = false;
      sendBtn.disabled = false;
      input.focus();
    }
    call("chat", { message: text, page: window.location.pathname })
      .then(function (r) {
        thinking.remove();
        if (r.reply) {
          addMessage("assistant", r.reply);
        }
        (r.proposals || []).forEach(function (p) {
          addProposal({ id: p.id, tool: p.tool, text: p.preview, status: "PENDING" });
        });
        done();
      })
      .catch(function (e) {
        thinking.remove();
        addMessage("error", e.message);
        done();
      });
  }

  form.addEventListener("submit", (ev) => {
    ev.preventDefault();
    send();
  });
  input.addEventListener("keydown", (ev) => {
    if (ev.key === "Enter" && !ev.shiftKey && !ev.isComposing) {
      ev.preventDefault();
      send();
    }
  });
  function clearLog() {
    while (log.firstChild) {
      log.removeChild(log.firstChild);
    }
  }

  resetBtn.addEventListener("click", function () {
    call("reset", {})
      .then(clearLog)
      .catch(function (e) {
        addMessage("error", e.message);
      });
  });

  function loadHistory() {
    call("history")
      .then(function (h) {
        clearLog();
        if (!h.ready) {
          addMessage("error", "The assistant is not configured yet. An administrator can set it up in Manage Jenkins → System → MajorDome.");
        }
        (h.entries || []).forEach(renderEntry);
      })
      .catch(function (e) {
        addMessage("error", e.message);
      });
  }

  if (floating) {
    setOpen(storageGet(OPEN_KEY) === "1");
  }
  loadHistory();
})();
