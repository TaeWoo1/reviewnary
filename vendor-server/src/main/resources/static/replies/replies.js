// The seller's reply approval page — docs/vendor_server_contract_v1.md §4.3, mockup confirmed 2026-10-21.
// The PC sends a draft here only when the seller presses 「휴대폰으로 승인 요청」 (D7); the seller reads the review,
// may rewrite the text, and approves exactly that version; a delegate then pastes it into Seller Center by hand.
//
// The session token lives in this tab's sessionStorage — a phone browser drops background tabs, and a reload must not
// cost the seller their place. It is gone when the tab closes. Every value from the server is written with
// textContent, never as HTML.
(function () {
  "use strict";

  var KEY = "reviewnary.replies.session";
  // HELD waits on the seller like a draft does: an approval held because its words lost their ground (2026-10-23).
  var AWAITING = ["AWAITING_APPROVAL", "RECHECK_NEEDED", "HELD"];
  var MOVING = ["APPROVED", "CLAIMED", "CANCEL_REQUESTED", "HOLD_REQUESTED"];
  // Not settled either way: listed with the moving ones until the delegate says what happened, never dropped.
  var IN_DOUBT = ["UNKNOWN"];
  var STATE = {
    AWAITING_APPROVAL: ["승인 기다리는 중", "info"],
    RECHECK_NEEDED: ["다시 확인 필요", "warn"],
    APPROVED: ["승인됨", "info"],
    CLAIMED: ["담당자가 등록하는 중", "info"],
    CANCEL_REQUESTED: ["취소 요청함", "warn"],
    POSTED: ["담당자가 등록함 · 확인 전", "neutral"],
    FAILED: ["담당자가 등록하지 못함", "neutral"],
    UNKNOWN: ["담당자가 확인 중", "warn"],
    CANCELLED: ["취소됨", "neutral"],
    INVALIDATED: ["요청이 끝남", "neutral"],
    HELD: ["승인 보류 · 다시 승인 필요", "warn"],
    HOLD_REQUESTED: ["승인 보류 · 담당자 확인 중", "warn"]
  };
  var SOURCE_KIND = { PRODUCT_KNOWLEDGE: "상품 지식", ORG_POLICY: "운영 정책" };
  var CHECKS = {
    CONTACT_PHONE: "전화번호로 보이는 부분이 있습니다.",
    CONTACT_EMAIL: "이메일 주소로 보이는 부분이 있습니다.",
    URL: "링크로 보이는 부분이 있습니다."
  };

  var session = read();
  var stores = [];
  var task = null;
  var editing = false;

  function $(id) { return document.getElementById(id); }

  function read() { try { return window.sessionStorage.getItem(KEY); } catch (e) { return null; } }

  function keep(token) {
    session = token;
    try {
      if (token) { window.sessionStorage.setItem(KEY, token); } else { window.sessionStorage.removeItem(KEY); }
    } catch (e) { /* private mode: the session stays in memory only */ }
  }

  function show(view) {
    ["login", "list", "task"].forEach(function (v) { $("view-" + v).hidden = v !== view; });
    window.scrollTo(0, 0);
  }

  function say(id, text) { var el = $(id); el.textContent = text || ""; el.hidden = !text; }

  function commandId() {
    if (window.crypto && window.crypto.randomUUID) { return window.crypto.randomUUID(); }
    var b = new Uint8Array(16);
    window.crypto.getRandomValues(b);
    return Array.prototype.map.call(b, function (x) { return (x + 256).toString(16).slice(1); }).join("");
  }

  function call(method, path, body) {
    var headers = { "Accept": "application/json" };
    if (body !== undefined) { headers["Content-Type"] = "application/json"; }
    if (session) { headers.Authorization = "Bearer " + session; }
    return fetch(path, {
      method: method, headers: headers, credentials: "omit",
      body: body === undefined ? undefined : JSON.stringify(body)
    }).then(function (r) {
      return r.text().then(function (t) {
        var json = null;
        try { json = t ? JSON.parse(t) : null; } catch (e) { json = null; }
        if (r.status === 401) { keep(null); show("login"); }
        return { status: r.status, json: json };
      });
    });
  }

  function stars(n) {
    var k = Math.max(0, Math.min(5, n | 0));
    return "★★★★★".slice(0, k) + "☆☆☆☆☆".slice(0, 5 - k);
  }

  function day(iso) {
    if (!iso) { return ""; }
    try {
      return new Date(iso).toLocaleDateString("ko-KR", { month: "long", day: "numeric", timeZone: "Asia/Seoul" });
    } catch (e) { return ""; }
  }

  function chip(el, state) {
    var s = STATE[state] || [state, "neutral"];
    el.textContent = s[0];
    el.className = "chip " + s[1];
  }

  function storeLabel(id) {
    for (var i = 0; i < stores.length; i++) { if (stores[i].id === id) { return stores[i].label; } }
    return "";
  }

  /* ── list ─────────────────────────────────────────────────────────────────────────────────────────── */

  function loadList(notice) {
    say("list-notice", notice || "");
    return call("GET", "/v1/stores").then(function (r) {
      if (r.status !== 200 || !Array.isArray(r.json)) { if (r.status !== 401) { listFailed(); } return; }
      stores = r.json;
      var states = AWAITING.concat(MOVING, IN_DOUBT);
      var calls = [];
      stores.forEach(function (s) {
        states.forEach(function (st) {
          calls.push(call("GET", "/v1/stores/" + s.id + "/tasks?state=" + st));
        });
      });
      return Promise.all(calls).then(function (answers) {
        var all = [];
        for (var i = 0; i < answers.length; i++) {
          if (answers[i].status !== 200 || !Array.isArray(answers[i].json)) { listFailed(); return; }
          all = all.concat(answers[i].json);
        }
        all.sort(function (a, b) { return String(b.updatedAt).localeCompare(String(a.updatedAt)); });
        renderList(all.filter(function (t) { return AWAITING.indexOf(t.state) >= 0; }),
          all.filter(function (t) { return MOVING.indexOf(t.state) >= 0 || IN_DOUBT.indexOf(t.state) >= 0; }));
        show("list");
      });
    }, listFailed);
  }

  function listFailed() {
    say("list-notice", "목록을 불러오지 못했습니다. 잠시 후 새로고침하세요.");
    $("list-notice").className = "msg bad-text";
    show("list");
  }

  function row(t) {
    var b = document.createElement("button");
    b.type = "button";
    b.className = "item stack8";
    var head = document.createElement("div");
    head.className = "between";
    var left = document.createElement("span");
    left.className = "row";
    var st = document.createElement("span");
    st.className = "stars";
    st.textContent = stars(t.review ? t.review.rating : 0);
    var meta = document.createElement("span");
    meta.className = "sm muted";
    var label = stores.length > 1 ? storeLabel(t.storeId) + " · " : "";
    meta.textContent = label + (t.review && t.review.productName ? t.review.productName : "");
    left.appendChild(st);
    left.appendChild(meta);
    var state = document.createElement("span");
    chip(state, t.state);
    head.appendChild(left);
    head.appendChild(state);
    var body = document.createElement("p");
    var text = t.review && t.review.body ? t.review.body : "";
    body.textContent = text.length > 60 ? text.slice(0, 60) + "…" : text;
    b.appendChild(head);
    b.appendChild(body);
    b.addEventListener("click", function () { open(t.id); });
    return b;
  }

  function renderList(awaiting, moving) {
    $("list-notice").className = "msg";
    $("awaiting-count").textContent = String(awaiting.length);
    var a = $("awaiting");
    a.textContent = "";
    awaiting.forEach(function (t) { a.appendChild(row(t)); });
    a.hidden = awaiting.length === 0;
    $("awaiting-empty").hidden = awaiting.length !== 0;
    var m = $("moving");
    m.textContent = "";
    moving.forEach(function (t) { m.appendChild(row(t)); });
    $("moving-count").textContent = String(moving.length);
    $("moving-section").hidden = moving.length === 0;
  }

  /* ── one task ─────────────────────────────────────────────────────────────────────────────────────── */

  function open(id) {
    say("task-error", "");
    return call("GET", "/v1/tasks/" + id).then(function (r) {
      if (r.status === 200 && r.json) { task = r.json; editing = false; renderTask(); show("task"); }
      else if (r.status !== 401) { loadList("이 답글을 불러오지 못했습니다. 목록을 다시 불러왔습니다."); }
    }, function () { say("task-error", "서버에 연결하지 못했습니다. 잠시 후 다시 시도하세요."); });
  }

  /**
   * Why a version was stopped, in the seller's words (owner decision 2026-10-23: what changed, which sentence, what
   * to do). Built from the PC's note only — the server judged nothing — and null when there is nothing to say.
   */
  function reason(state, note) {
    if (!note) { return null; }
    var codes = note.codes || [];
    var src = (note.sources || [])[0];
    // 「…」 문서가: the particle follows 문서, never the seller's own title, so it is always right.
    var doc = src ? (SOURCE_KIND[src.kind] || "자료") + " 「" + src.title + "」 문서가 " : "";
    var changed = src ? (src.removed ? "사용 중지" : day(src.changedAt) + "에 고쳐") : "";
    var promises = (note.promises || []).map(function (p) { return "「" + p + "」"; }).join(" · ");
    if (codes.indexOf("UNSUPPORTED_PROMISE") >= 0 && (state === "APPROVED" || state === "CLAIMED")) {
      // The PC flagged it before approval and the seller approved it anyway: their word, not a hold.
      return { text: "근거에 없는 " + promises + " 약속을 확인하고 승인한 답글입니다.", todo: "" };
    }
    if (codes.indexOf("UNSUPPORTED_PROMISE") >= 0) {
      return {
        text: (src ? doc + changed + (src.removed ? "되어, " : "져, ") : "") + "이 답글의 " + promises
          + " 약속이 지금 근거와 맞지 않습니다.",
        todo: state === "AWAITING_APPROVAL" ? "이 약속이 맞는지 확인하고 승인하세요. 맞다면 PC에서 근거에 먼저 적어 두는 것이 좋습니다."
          : state === "RECHECK_NEEDED" ? "PC에서 초안을 고쳐 다시 보내 주세요."
          : "문장을 고쳐 다시 승인하세요. 그 약속이 맞다면 PC에서 근거를 먼저 고쳐 주세요."
      };
    }
    if (codes.indexOf("SOURCE_CHANGED") >= 0 && src) {
      if (state === "APPROVED" || state === "CLAIMED") {
        return { text: "근거로 쓴 " + doc + changed + (src.removed ? "됐지만" : "졌지만")
          + ", 이 답글과 부딪히는 곳이 없어 승인을 그대로 둡니다.", todo: "" };
      }
      return {
        text: "이 초안이 근거로 쓴 " + doc + changed + (src.removed ? "됐습니다." : "졌습니다.")
          + " 어느 문장이 달라졌는지는 아직 짚지 못합니다.",
        todo: "PC에서 바뀐 문서와 초안을 비교해 보고, 맞으면 그대로, 아니면 고쳐서 다시 보내 주세요."
      };
    }
    return null;
  }

  /** The reply, with each sentence the hold is about marked. Text nodes only — the server's words are never HTML. */
  function reply(el, body, sentences) {
    while (el.firstChild) { el.removeChild(el.firstChild); }
    var at = 0;
    var marks = [];
    sentences.forEach(function (s) {
      var i = body.indexOf(s);
      if (i >= 0) { marks.push([i, i + s.length]); }
    });
    marks.sort(function (a, b) { return a[0] - b[0]; });
    marks.forEach(function (m) {
      if (m[0] < at) { return; }
      el.appendChild(document.createTextNode(body.slice(at, m[0])));
      var span = document.createElement("span");
      span.className = "hl";
      span.textContent = body.slice(m[0], m[1]);
      el.appendChild(span);
      at = m[1];
    });
    el.appendChild(document.createTextNode(body.slice(at)));
  }

  function renderTask() {
    var t = task;
    var rv = t.review || {};
    chip($("task-state"), t.state);
    $("review-stars").textContent = stars(rv.rating);
    $("review-meta").textContent = [rv.productName || "", day(rv.writtenAt)].filter(Boolean).join(" · ");
    $("review-body").textContent = rv.body || "";
    var att = rv.attachment;
    $("review-attachment").hidden = !att;
    if (att) {
      $("review-photo").src = att.url;
      $("review-attachment-note").textContent = (att.kind === "VIDEO_STILL" ? "영상 첫 장면" : "사진")
        + " 1장 · 나머지 첨부는 파일로 받을 수 없음";
    } else {
      $("review-photo").removeAttribute("src");
    }
    var flag = rv.replied ? "이 리뷰에는 이미 답글이 있습니다." : "";
    say("review-flag", flag);

    var awaiting = t.state === "AWAITING_APPROVAL";
    var editable = AWAITING.indexOf(t.state) >= 0;
    var cancellable = ["AWAITING_APPROVAL", "RECHECK_NEEDED", "APPROVED", "CLAIMED", "HELD", "HOLD_REQUESTED"]
      .indexOf(t.state) >= 0;

    var note = t.basisNote && t.basisNoteVersion === t.draftVersion ? t.basisNote : null;
    reply($("reply-body"), t.draftBody || "", note ? note.sentences || [] : []);
    var why = reason(t.state, note);
    $("why").hidden = !why || editing;
    $("why-text").textContent = why ? why.text : "";
    $("why-todo").textContent = why ? why.todo : "";
    $("why-todo").hidden = !why || !why.todo;
    $("edited").hidden = !t.editedOnMobile;
    var checks = (t.serverChecks || []).map(function (c) { return CHECKS[c]; }).filter(Boolean);
    $("checks").hidden = checks.length === 0 || !editable;
    $("checks-text").textContent = checks.length ? checks.join(" ") + " 확인하고 승인하세요." : "";
    // A phone edit skipped the PC's knowledge checks (grounds · banned phrases); the server only looks for contacts
    // and links. Until the PC has checked this exact version, say so rather than let the edit pass for a checked draft.
    var unchecked = "";
    if (t.editedOnMobile && t.pcRecheckVersion !== t.draftVersion) {
      unchecked = "휴대폰에서 고친 문장은 PC의 근거 · 금지 표현 검사를 거치지 않았습니다. 서버는 연락처 · 링크만 봅니다. "
        + "내용을 직접 확인하고 승인하세요.";
    } else if (t.editedOnMobile && String(t.pcRecheck || "").indexOf("FLAGGED") === 0 && !note) {
      unchecked = "PC 검사에서 확인할 부분이 나왔습니다. PC에서 초안을 확인하세요.";
    }
    $("unchecked").hidden = !unchecked || !editable;
    $("unchecked-text").textContent = unchecked;

    $("read-reply").hidden = editing;
    $("edit-form").hidden = !editing;
    $("approved-note").hidden = editing || ["APPROVED", "CLAIMED"].indexOf(t.state) < 0;
    $("approve").hidden = editing || !awaiting;
    $("save-edit").hidden = !editing;
    $("save-note").hidden = !editing;
    $("edit").hidden = editing || !editable;
    $("cancel-edit").hidden = !editing;
    $("cancel").hidden = editing || !cancellable;
    $("cancel").textContent = t.state === "APPROVED" ? "승인 취소"
      : t.state === "CLAIMED" || t.state === "HOLD_REQUESTED" ? "취소 요청"
      : t.state === "HELD" ? "답글 취소" : "요청 취소";
    $("confirm-cancel").hidden = true;

    var stateNote = "";
    if (t.state === "RECHECK_NEEDED" && !note) {
      stateNote = "응대 기준이 바뀌어 이 초안은 승인할 수 없습니다. PC에서 초안을 확인하고 다시 보내 주세요.";
    } else if (t.state === "HOLD_REQUESTED") {
      stateNote = "담당자에게도 멈춤을 전했습니다. 등록 전인지 담당자가 확인하는 중입니다.";
    } else if (t.state === "CLAIMED") {
      stateNote = "담당자가 등록을 시작했습니다. 취소하면 담당자가 등록 전인지 확인한 뒤 끝납니다.";
    } else if (t.state === "CANCEL_REQUESTED") {
      stateNote = "취소를 요청했습니다. 담당자가 등록 전인지 확인한 뒤 끝납니다.";
    } else if (t.state === "UNKNOWN") {
      stateNote = "담당자가 등록했는지 아직 확인되지 않았습니다. 확인될 때까지 이 리뷰에는 새 답글을 보내지 않습니다.";
    }
    say("state-note", stateNote);
  }

  function refused(r, fallback) {
    var code = r.json && r.json.error;
    if (code === "stale" || code === "fingerprint_mismatch" || code === "wrong_state") {
      return open(task.id).then(function () {
        say("task-error", "그 사이에 답글이 바뀌었습니다. 지금 내용을 다시 보여 드립니다.");
      });
    }
    if (code === "policy_changed") {
      return open(task.id).then(function () {
        say("task-error", "응대 기준이 바뀌어 승인할 수 없습니다. PC에서 초안을 다시 보내 주세요.");
      });
    }
    if (code === "invalid_body") { say("task-error", "답글이 비었거나 너무 깁니다."); return; }
    say("task-error", fallback);
  }

  function busy(on) {
    ["approve", "save-edit", "edit", "cancel", "cancel-yes"].forEach(function (id) { $(id).disabled = on; });
  }

  $("approve").addEventListener("click", function () {
    say("task-error", "");
    busy(true);
    call("POST", "/v1/tasks/" + task.id + "/approve",
      { version: task.draftVersion, fingerprint: task.draftFingerprint, commandId: commandId() })
      .then(function (r) {
        busy(false);
        if (r.status === 200 && r.json) { task = r.json; renderTask(); window.scrollTo(0, 0); }
        else if (r.status !== 401) { return refused(r, "승인하지 못했습니다. 잠시 후 다시 시도하세요."); }
      }, function () { busy(false); say("task-error", "서버에 연결하지 못했습니다. 승인되지 않았습니다."); });
  });

  $("edit").addEventListener("click", function () {
    editing = true;
    $("edit-body").value = task.draftBody || "";
    say("task-error", "");
    renderTask();
    $("edit-body").focus();
  });

  $("cancel-edit").addEventListener("click", function () { editing = false; say("task-error", ""); renderTask(); });

  $("edit-form").addEventListener("submit", function (e) { e.preventDefault(); });

  $("save-edit").addEventListener("click", function () {
    var body = $("edit-body").value;
    if (!body.trim()) { say("task-error", "답글이 비어 있습니다."); return; }
    say("task-error", "");
    busy(true);
    call("PUT", "/v1/tasks/" + task.id + "/draft", { baseVersion: task.draftVersion, body: body })
      .then(function (r) {
        busy(false);
        if (r.status === 200 && r.json) { task = r.json; editing = false; renderTask(); }
        else if (r.status !== 401) { return refused(r, "저장하지 못했습니다. 잠시 후 다시 시도하세요."); }
      }, function () { busy(false); say("task-error", "서버에 연결하지 못했습니다. 저장되지 않았습니다."); });
  });

  $("cancel").addEventListener("click", function () {
    $("confirm-cancel-text").textContent = task.state === "CLAIMED"
      ? "담당자에게 등록을 멈춰 달라고 요청할까요?"
      : task.state === "APPROVED" ? "승인을 취소할까요? 담당자에게 가지 않습니다." : "이 요청을 취소할까요?";
    $("confirm-cancel").hidden = false;
    $("cancel-yes").focus();
  });

  $("cancel-no").addEventListener("click", function () { $("confirm-cancel").hidden = true; });

  $("cancel-yes").addEventListener("click", function () {
    say("task-error", "");
    busy(true);
    call("POST", "/v1/tasks/" + task.id + "/cancel", { commandId: commandId() }).then(function (r) {
      busy(false);
      if (r.status === 200 && r.json) {
        if (r.json.state === "CANCELLED") { loadList("취소했습니다."); } else { task = r.json; renderTask(); }
      } else if (r.status !== 401) { return refused(r, "취소하지 못했습니다. 잠시 후 다시 시도하세요."); }
    }, function () { busy(false); say("task-error", "서버에 연결하지 못했습니다. 취소되지 않았습니다."); });
  });

  $("back").addEventListener("click", function () { loadList(); });
  $("refresh").addEventListener("click", function () { loadList(); });
  // The server ends this session too, so a copy of the token left anywhere stops working; the page signs out
  // whether or not that answer arrives.
  $("logout").addEventListener("click", function () {
    if (session) { call("POST", "/v1/auth/logout").then(null, function () {}); }
    keep(null);
    show("login");
    $("email").focus();
  });

  $("login-form").addEventListener("submit", function (e) {
    e.preventDefault();
    say("login-error", "");
    call("POST", "/v1/auth/login", { email: $("email").value.trim(), password: $("password").value })
      .then(function (r) {
        $("password").value = "";
        if (r.status === 200 && r.json && r.json.token) { keep(r.json.token); loadList(); }
        else if (r.status === 429) { say("login-error", "로그인 시도가 많았습니다. 15분 뒤 다시 시도하세요."); }
        else if (r.status === 401 || r.status === 400) { say("login-error", "이메일 또는 비밀번호가 맞지 않습니다."); }
        else { say("login-error", "서버에 연결하지 못했습니다. 잠시 후 다시 시도하세요."); }
      }, function () { say("login-error", "서버에 연결하지 못했습니다. 잠시 후 다시 시도하세요."); });
  });

  if (session) { loadList(); } else { show("login"); $("email").focus(); }
})();
