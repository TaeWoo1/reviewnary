// The seller's 「할 일」 on the phone — docs/vendor_server_contract_v1.md §4.3; mockup ④ confirmed 2026-10-25.
// One draft, either device (D7 as revised 2026-10-25): every review draft the seller's PC writes is here as it is on
// the PC. The seller reads the review, may rewrite the text — it saves itself after a pause — and presses the same
// 「답글 등록 요청」 as on the PC, which approves exactly the version on screen; a delegate then pastes it into Seller
// Center by hand. A version written here waits for the PC's check before any delegate sees it.
//
// The session token lives in this tab's sessionStorage — a phone browser drops background tabs, and a reload must not
// cost the seller their place. It is gone when the tab closes. Every value from the server is written with
// textContent, never as HTML.
(function () {
  "use strict";

  var KEY = "reviewnary.replies.session";
  var SAVE_AFTER_MS = 1500;
  // The PC asks the server every few minutes; a link unused for longer than this is a PC that is off.
  var PC_OFF_AFTER_MS = 15 * 60 * 1000;
  // The seller's to do: a draft to read and request, one held or sent back, and a delegate's failure.
  var TODO = ["AWAITING_APPROVAL", "RECHECK_NEEDED", "HELD", "FAILED"];
  // Someone else's to finish — the PC's check, a delegate, the channel. Never dropped while unsettled.
  var MOVING = ["APPROVED", "CLAIMED", "CANCEL_REQUESTED", "HOLD_REQUESTED", "UNKNOWN", "POSTED"];
  var DONE = ["CANCELLED", "INVALIDATED"];
  var EDITABLE = ["AWAITING_APPROVAL", "RECHECK_NEEDED", "HELD"];
  var CANCELLABLE = ["APPROVED", "CLAIMED", "HELD", "HOLD_REQUESTED"];
  var DONE_SHOWN = 30;
  // The PC's words for the same states (DelegatedReplyControls): short labels, no sentence a label can carry.
  var STATE = {
    RECHECK_NEEDED: ["다시 검사 중", "neutral"],
    APPROVED: ["담당자 대기", "info"],
    CLAIMED: ["등록하는 중", "info"],
    CANCEL_REQUESTED: ["취소 요청 중", "warn"],
    POSTED: ["등록함", "neutral"],
    FAILED: ["등록 못 함", "warn"],
    UNKNOWN: ["결과 확인 중", "warn"],
    CANCELLED: ["요청 취소함", "neutral"],
    INVALIDATED: ["요청 끝남", "neutral"],
    HELD: ["고칠 곳", "warn"],
    HOLD_REQUESTED: ["멈춤 요청 중", "warn"]
  };
  var SOURCE_KIND = { PRODUCT_KNOWLEDGE: "상품 지식", ORG_POLICY: "운영 정책" };
  var CHECKS = {
    CONTACT_PHONE: "전화번호로 보이는 부분이 있습니다.",
    CONTACT_EMAIL: "이메일 주소로 보이는 부분이 있습니다.",
    URL: "링크로 보이는 부분이 있습니다."
  };

  var session = read();
  var stores = [];
  var lists = { todo: [], moving: [], done: [] };
  var tab = "todo";
  var task = null;
  var timer = null;
  var saving = false;
  var saveFailed = false;
  var dirty = false;

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

  function clock(iso) {
    if (!iso) { return ""; }
    try {
      return new Date(iso).toLocaleTimeString("ko-KR",
        { hour: "2-digit", minute: "2-digit", hour12: false, timeZone: "Asia/Seoul" });
    } catch (e) { return ""; }
  }

  /** The phone version the PC has not checked yet — the server's own rule (ReplyTasks.awaitingPcCheck). */
  function pcPending(t) {
    return !!t.editedOnMobile && t.pcRecheckVersion !== t.draftVersion;
  }

  /** The PC's finding on this exact version, when it found something. */
  function noteOf(t) {
    return t.basisNote && t.basisNoteVersion === t.draftVersion ? t.basisNote : null;
  }

  function toFix(note) {
    return note ? Math.max(1, (note.promises || []).length + (note.phrases || []).length) : 0;
  }

  /** The chip a task wears, or null when it is only a draft waiting to be read. */
  function word(t) {
    if (t.state === "AWAITING_APPROVAL") { return null; }
    if (t.state === "APPROVED" && pcPending(t)) { return ["검사 대기", "warn"]; }
    // The channel's own export shows a reply: the one state here that is more than someone's report.
    if (t.state === "POSTED" && t.review && t.review.replied) { return ["네이버 확인", "good"]; }
    if (t.state === "HELD") { return ["고칠 곳 " + toFix(noteOf(t)), "warn"]; }
    return STATE[t.state] || [t.state, "neutral"];
  }

  function chip(el, t) {
    var w = word(t);
    el.hidden = !w;
    el.textContent = w ? w[0] : "";
    el.className = "chip " + (w ? w[1] : "");
  }

  function storeLabel(id) {
    for (var i = 0; i < stores.length; i++) { if (stores[i].id === id) { return stores[i].label; } }
    return "";
  }

  /* ── list ─────────────────────────────────────────────────────────────────────────────────────────── */

  function loadList(notice) {
    say("list-notice", notice || "");
    pcOff();
    return call("GET", "/v1/stores").then(function (r) {
      if (r.status !== 200 || !Array.isArray(r.json)) { if (r.status !== 401) { listFailed(); } return; }
      stores = r.json;
      var states = TODO.concat(MOVING, DONE);
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
        // One row per review: its newest task. An older failure is not news once the review was asked again.
        var seen = {};
        all = all.filter(function (t) {
          if (seen[t.reviewId]) { return false; }
          seen[t.reviewId] = true;
          return true;
        });
        lists = {
          todo: all.filter(function (t) { return TODO.indexOf(t.state) >= 0; }),
          moving: all.filter(function (t) {
            return MOVING.indexOf(t.state) >= 0 && !(t.state === "POSTED" && t.review && t.review.replied);
          }),
          done: all.filter(function (t) {
            return DONE.indexOf(t.state) >= 0 || (t.state === "POSTED" && t.review && t.review.replied);
          }).slice(0, DONE_SHOWN)
        };
        renderList();
        show("list");
      });
    }, listFailed);
  }

  /** 「PC 꺼짐」: none of the seller's PCs has used its link lately. Said only when the server answered. */
  function pcOff() {
    call("GET", "/v1/installs").then(function (r) {
      if (r.status !== 200 || !Array.isArray(r.json)) { $("pc-off").hidden = true; return; }
      var latest = 0;
      r.json.forEach(function (i) {
        var at = i.lastUsedAt ? Date.parse(i.lastUsedAt) : 0;
        if (at > latest) { latest = at; }
      });
      $("pc-off").hidden = r.json.length === 0 || Date.now() - latest < PC_OFF_AFTER_MS;
    }, function () { $("pc-off").hidden = true; });
  }

  function listFailed() {
    say("list-notice", "목록을 불러오지 못했습니다. 잠시 후 새로고침하세요.");
    $("list-notice").className = "msg bad-text";
    show("list");
  }

  /** One line under a row: what the seller would want to know before opening it. */
  function rowNote(t) {
    var note = noteOf(t);
    if (EDITABLE.indexOf(t.state) >= 0 && note) { return "고칠 곳 " + toFix(note); }
    if (t.state === "AWAITING_APPROVAL") { return "초안 있음"; }
    if (t.state === "FAILED") { return "PC에서 다시 요청하거나 직접 등록"; }
    if (t.state === "APPROVED" && pcPending(t)) { return "PC가 켜지면 검사"; }
    if (t.state === "POSTED" && !(t.review && t.review.replied)) { return "다음 엑셀에서 네이버 확인"; }
    if (t.state === "UNKNOWN") { return "담당자가 등록 여부 확인 중"; }
    return "";
  }

  function row(t) {
    var b = document.createElement("button");
    b.type = "button";
    b.className = "item stack8";
    var head = document.createElement("div");
    head.className = "between";
    var body = document.createElement("p");
    var text = t.review && t.review.body ? t.review.body : "";
    body.textContent = text.length > 60 ? text.slice(0, 60) + "…" : text;
    var state = document.createElement("span");
    chip(state, t);
    head.appendChild(body);
    head.appendChild(state);
    var meta = document.createElement("span");
    meta.className = "sm muted";
    var label = stores.length > 1 ? storeLabel(t.storeId) : "";
    meta.textContent = [rowNote(t), label, t.review && t.review.productName ? t.review.productName : "",
      stars(t.review ? t.review.rating : 0)].filter(Boolean).join(" · ");
    b.appendChild(head);
    b.appendChild(meta);
    b.addEventListener("click", function () { open(t.id); });
    return b;
  }

  function renderList() {
    $("list-notice").className = "msg";
    ["todo", "moving", "done"].forEach(function (k) {
      $(k + "-count").textContent = k === "done" ? "" : String(lists[k].length);
      var el = $("list-" + k);
      el.textContent = "";
      lists[k].forEach(function (t) { el.appendChild(row(t)); });
      el.hidden = k !== tab || lists[k].length === 0;
    });
    Array.prototype.forEach.call(document.querySelectorAll(".tab"), function (b) {
      var on = b.getAttribute("data-tab") === tab;
      b.className = "tab" + (on ? " on" : "");
      b.setAttribute("aria-pressed", on ? "true" : "false");
    });
    var empty = { todo: "할 일이 없습니다.", moving: "진행 중인 답글이 없습니다.", done: "끝난 답글이 없습니다." };
    say("list-empty", lists[tab].length === 0 ? empty[tab] : "");
  }

  Array.prototype.forEach.call(document.querySelectorAll(".tab"), function (b) {
    b.addEventListener("click", function () { tab = b.getAttribute("data-tab"); renderList(); });
  });

  /* ── one task ─────────────────────────────────────────────────────────────────────────────────────── */

  function open(id) {
    say("task-error", "");
    return call("GET", "/v1/tasks/" + id).then(function (r) {
      if (r.status === 200 && r.json) {
        task = r.json;
        dirty = false;
        saveFailed = false;
        renderTask(true);
        show("task");
      } else if (r.status !== 401) { loadList("이 답글을 불러오지 못했습니다. 목록을 다시 불러왔습니다."); }
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
    var phrases = (note.phrases || []).map(function (p) { return "「" + p + "」"; }).join(" · ");
    var promise = codes.indexOf("UNSUPPORTED_PROMISE") >= 0;
    var forbidden = codes.indexOf("FORBIDDEN_PHRASE") >= 0 && phrases;
    if ((promise || forbidden) && (state === "APPROVED" || state === "CLAIMED")) {
      // The PC flagged it before approval and the seller approved it anyway: their word, not a hold.
      return { text: "확인하고 승인한 답글입니다 — " + [promise ? "근거에 없는 약속 " + promises : "",
        forbidden ? "쓰지 않기로 한 표현 " + phrases : ""].filter(Boolean).join(", ") + ".", todo: "" };
    }
    if (forbidden && !promise) {
      return {
        text: "회사가 쓰지 않기로 한 표현이 있습니다: " + phrases + ".",
        todo: state === "HELD" ? "그 표현을 빼고 고친 뒤 다시 요청하세요." : "그 표현을 빼고 고치면 요청할 수 있습니다."
      };
    }
    if (promise) {
      return {
        text: (src ? doc + changed + (src.removed ? "되어, " : "져, ") : "") + "이 답글의 " + promises
          + " 약속이 지금 근거와 맞지 않습니다." + (forbidden ? " 회사가 쓰지 않기로 한 표현도 있습니다: " + phrases + "." : ""),
        todo: state === "AWAITING_APPROVAL" ? "이 약속이 맞는지 보고 요청하세요. 맞다면 PC에서 근거에 먼저 적어 두면 좋습니다."
          : "문장을 고쳐 다시 요청하세요. 그 약속이 맞다면 PC에서 근거를 먼저 고쳐 주세요."
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
        todo: "바뀐 문서와 비교해 보고, 맞으면 그대로, 아니면 고쳐 주세요. PC가 다시 검사합니다."
      };
    }
    return null;
  }

  /** The reply, with each sentence a finding is about marked. Text nodes only — the server's words are never HTML. */
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

  /** The line beside 「답변 초안」, as on the PC: saving, not saved, something to fix, or checked. */
  function draftStatus(t, editable) {
    var el = $("draft-status");
    var note = noteOf(t);
    var text = "";
    var cls = "xs strong ";
    if (!editable) { text = ""; }
    else if (saveFailed) { text = "저장 실패 · 다시 시도"; cls += "bad-text"; }
    else if (saving || dirty) { text = "저장 중…"; cls += "muted"; }
    else if (note && (note.codes || []).some(function (c) { return c !== "SOURCE_CHANGED"; })) {
      text = "고칠 곳 " + toFix(note); cls += "bad-text";
    } else if (pcPending(t)) { text = "PC 검사 전"; cls += "muted"; }
    else if (t.state === "RECHECK_NEEDED") { text = "다시 검사 중"; cls += "muted"; }
    else { text = "PC 검사 통과"; cls += "good-text"; }
    el.textContent = text;
    el.className = cls;
  }

  function renderTask(fresh) {
    var t = task;
    var rv = t.review || {};
    chip($("task-state"), t);
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
    say("review-flag", rv.replied ? "이 리뷰에는 이미 답글이 있습니다." : "");

    var editable = EDITABLE.indexOf(t.state) >= 0;
    var note = noteOf(t);
    $("reply-edit").hidden = !editable;
    $("reply-body").hidden = editable;
    $("draft-label").textContent = editable ? "답변 초안" : "답글";
    if (editable && (fresh || !dirty)) { $("reply-edit").value = t.draftBody || ""; }
    if (!editable) { reply($("reply-body"), t.draftBody || "", note ? note.sentences || [] : []); }
    draftStatus(t, editable);

    var why = reason(t.state, note);
    $("why").hidden = !why;
    $("why-text").textContent = why ? why.text : "";
    $("why-todo").textContent = why ? why.todo : "";
    $("why-todo").hidden = !why || !why.todo;
    var checks = (t.serverChecks || []).map(function (c) { return CHECKS[c]; }).filter(Boolean);
    $("checks").hidden = checks.length === 0 || !editable;
    $("checks-text").textContent = checks.length ? checks.join(" ") + " 확인하고 요청하세요." : "";

    // A banned phrase the PC found in this version is fixed before the request (2026-10-23); a promise is advice.
    var banned = !!note && (note.codes || []).indexOf("FORBIDDEN_PHRASE") >= 0;
    var requestable = t.state === "AWAITING_APPROVAL" && !banned;
    $("approve").hidden = !requestable;
    $("approve").disabled = saving || dirty;
    $("approve-note").hidden = !requestable;

    var since = clock(t.approvedAt || t.updatedAt);
    var stateNote = "";
    if (t.state === "APPROVED") {
      stateNote = since + " 요청" + (pcPending(t) ? " · PC가 켜지면 검사 후 담당자에게 넘어갑니다." : "");
    } else if (t.state === "CLAIMED") { stateNote = since + " 요청 · 담당자가 등록 시작"; }
    else if (t.state === "CANCEL_REQUESTED") { stateNote = "담당자 응답 대기"; }
    else if (t.state === "HOLD_REQUESTED") { stateNote = "담당자가 등록 전인지 확인하면 바뀝니다"; }
    else if (t.state === "UNKNOWN") { stateNote = "담당자가 등록 여부 확인 중 · 중복 방지로 다시 요청 불가"; }
    else if (t.state === "POSTED") { stateNote = rv.replied ? "네이버에 답글 있음" : "다음 엑셀에서 네이버 확인"; }
    else if (t.state === "FAILED") { stateNote = "PC에서 다시 요청하거나 직접 등록"; }
    else if (t.state === "INVALIDATED") { stateNote = "리뷰가 바뀌었거나 이미 답글이 있음"; }
    else if (t.state === "RECHECK_NEEDED" && !note) { stateNote = "응대 기준이 바뀌어 PC가 다시 검사합니다"; }
    say("state-note", stateNote);

    var cancellable = CANCELLABLE.indexOf(t.state) >= 0;
    $("cancel").hidden = !cancellable;
    $("cancel").textContent = t.state === "CLAIMED" || t.state === "HOLD_REQUESTED" ? "취소 요청" : "요청 취소";
    $("confirm-cancel").hidden = true;
  }

  function refused(r, fallback) {
    var code = r.json && r.json.error;
    if (code === "stale" || code === "fingerprint_mismatch" || code === "wrong_state") {
      return open(task.id).then(function () {
        say("task-error", "그 사이에 문장이 바뀌었습니다. 지금 내용을 다시 보여 드립니다.");
      });
    }
    if (code === "policy_changed") {
      return open(task.id).then(function () {
        say("task-error", "응대 기준이 바뀌어 PC가 다시 검사합니다. 검사가 끝나면 요청할 수 있습니다.");
      });
    }
    if (code === "invalid_body") { say("task-error", "답글이 비었거나 너무 깁니다."); return; }
    if (code === "forbidden_phrase") {
      say("task-error", "회사가 쓰지 않기로 한 표현이 있어 요청할 수 없습니다. 문장을 고친 뒤 요청하세요.");
      return;
    }
    say("task-error", fallback);
  }

  /** Saves the box as the task's next version — the same draft the PC shows. A version it did not see is not replaced. */
  function save() {
    if (timer) { clearTimeout(timer); timer = null; }
    if (!task || saving || !dirty) { return Promise.resolve(); }
    var body = $("reply-edit").value;
    if (!body.trim()) { saveFailed = true; draftStatus(task, true); return Promise.resolve(); }
    saving = true;
    saveFailed = false;
    draftStatus(task, true);
    renderTask(false);
    return call("PUT", "/v1/tasks/" + task.id + "/draft", { baseVersion: task.draftVersion, body: body })
      .then(function (r) {
        saving = false;
        if (r.status === 200 && r.json) {
          task = r.json;
          dirty = $("reply-edit").value !== body;
          renderTask(false);
          if (dirty) { later(); }
        } else if (r.status !== 401) {
          if (r.json && r.json.error === "stale") {
            // The PC wrote first. Its words lead; the ones typed here are said back, not thrown away.
            var mine = body;
            return open(task.id).then(function () {
              say("task-error", "PC에서 먼저 고쳐 그 문장을 보여 드립니다. 여기서 쓰던 문장: " + mine);
            });
          }
          saveFailed = true;
          renderTask(false);
        }
      }, function () { saving = false; saveFailed = true; renderTask(false); });
  }

  function later() {
    if (timer) { clearTimeout(timer); }
    timer = setTimeout(save, SAVE_AFTER_MS);
  }

  $("reply-edit").addEventListener("input", function () {
    dirty = true;
    saveFailed = false;
    draftStatus(task, true);
    $("approve").disabled = true;
    later();
  });
  $("reply-edit").addEventListener("blur", function () { if (dirty) { save(); } });
  $("draft-status").addEventListener("click", function () { if (saveFailed) { dirty = true; save(); } });

  $("approve").addEventListener("click", function () {
    if (saving || dirty) { return; }
    say("task-error", "");
    $("approve").disabled = true;
    call("POST", "/v1/tasks/" + task.id + "/approve",
      { version: task.draftVersion, fingerprint: task.draftFingerprint, commandId: commandId() })
      .then(function (r) {
        if (r.status === 200 && r.json) { task = r.json; renderTask(false); window.scrollTo(0, 0); }
        else if (r.status !== 401) { $("approve").disabled = false; return refused(r, "요청하지 못했습니다. 잠시 후 다시 시도하세요."); }
      }, function () { $("approve").disabled = false; say("task-error", "서버에 연결하지 못했습니다. 요청되지 않았습니다."); });
  });

  $("cancel").addEventListener("click", function () {
    $("confirm-cancel-text").textContent = task.state === "CLAIMED" || task.state === "HOLD_REQUESTED"
      ? "담당자에게 등록을 멈춰 달라고 요청할까요?" : "이 요청을 취소할까요? 담당자에게 가지 않습니다.";
    $("confirm-cancel").hidden = false;
    $("cancel-yes").focus();
  });

  $("cancel-no").addEventListener("click", function () { $("confirm-cancel").hidden = true; });

  $("cancel-yes").addEventListener("click", function () {
    say("task-error", "");
    $("cancel-yes").disabled = true;
    call("POST", "/v1/tasks/" + task.id + "/cancel", { commandId: commandId() }).then(function (r) {
      $("cancel-yes").disabled = false;
      if (r.status === 200 && r.json) {
        if (r.json.state === "CANCELLED") { loadList("취소했습니다."); } else { task = r.json; renderTask(false); }
      } else if (r.status !== 401) { return refused(r, "취소하지 못했습니다. 잠시 후 다시 시도하세요."); }
    }, function () { $("cancel-yes").disabled = false; say("task-error", "서버에 연결하지 못했습니다. 취소되지 않았습니다."); });
  });

  function back() {
    var pending = dirty ? save() : Promise.resolve();
    pending.then(function () { loadList(); });
  }

  $("back").addEventListener("click", back);
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
