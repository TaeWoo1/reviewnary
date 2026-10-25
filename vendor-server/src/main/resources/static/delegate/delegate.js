// The delegate's desk — docs/vendor_server_contract_v1.md §4.3. Opened in a PC browser beside Seller Center.
//
// One page, 「오늘 할 일」, in the order the work has to happen (owner decision 2026-10-26: no tabs to find a due
// upload behind): ① each assigned store's review export, with the exact period to download, then ② the approved
// replies to register — claim one, look once more just before saving, paste the exact approved text by hand, and
// report what Seller Center shows. This page and this server post nothing anywhere.
//
// Copying the reply and reporting it posted open only after a clear pre-save check (`clearToPost`); a check that
// fails or cannot be read means do not post. The session token lives in this tab's sessionStorage and is gone when
// the tab closes. Every value from the server is written with textContent, never as HTML.
//
// Google sign-in (owner, 2026-10-26) ends here: Google's return lands on /delegate#google=<handoff> (or
// #google-error=<reason>), and this page finishes it with the binding the starting tab kept in sessionStorage. A
// join that came back with the wrong Google account kept its invite code beside the binding (join.js), so this page
// can name the invited address and send the same tab to Google again.
(function () {
  "use strict";

  var KEY = "reviewnary.delegate.session";
  var GOOGLE_KEY = "reviewnary.delegate.google";
  var GOOGLE_ERRORS = {
    cancelled: "구글 로그인을 취소했습니다.",
    expired: "구글 로그인 시간이 지났습니다. 다시 눌러 주세요.",
    google_failed: "구글 로그인을 마치지 못했습니다. 이 창에서 다시 눌러 주세요.",
    email_mismatch: "초대받은 주소와 다른 구글 계정입니다. 초대 링크를 다시 열고 초대받은 주소의 구글 계정을 고르세요.",
    already_registered: "이미 등록된 담당자입니다. 여기서 로그인하세요.",
    invalid_invite: "초대 링크가 맞지 않거나 이미 썼거나 기한이 지났습니다. 초대한 사람에게 새 링크를 받으세요.",
    not_registered: "이 구글 계정으로 등록된 담당자가 없습니다. 초대받은 주소의 구글 계정인지 확인하세요.",
    not_enabled: "구글 로그인이 꺼져 있습니다. 비밀번호와 인증 앱으로 로그인하세요."
  };
  var MINE = ["CLAIMED", "CANCEL_REQUESTED", "HOLD_REQUESTED", "UNKNOWN"];
  var STATE = {
    APPROVED: ["승인됨", "info"],
    CLAIMED: ["등록하는 중", "info"],
    CANCEL_REQUESTED: ["판매자가 취소 요청", "warn"],
    HOLD_REQUESTED: ["판매자가 승인 보류", "warn"],
    UNKNOWN: ["확인 필요", "warn"]
  };
  var NO_FILE = ".xlsx · 10MB까지";
  var MAX_UPLOAD = 10 * 1024 * 1024;
  var DAY = 24 * 60 * 60 * 1000;

  var session = read();
  var task = null;
  var check = null;
  var copiedReply = false;
  var useRecovery = false;
  var googleOn = false;
  var retryInvite = null;
  var storeList = [];
  var openStore = null;
  var panelClosed = false;
  var chosen = null;
  var uploading = false;
  var ticking = null;

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
    $("top").hidden = view === "login";
    // On the list the header spans the list's column; on a task it spans the two panes.
    $("top").className = view === "list" ? "top narrow" : "top";
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
        if (r.status === 401 && path.indexOf("/v1/delegate/auth/") !== 0) { keep(null); show("login"); }
        return { status: r.status, json: json };
      });
    });
  }

  function chip(el, state) {
    var s = STATE[state] || [state, "neutral"];
    el.textContent = s[0];
    el.className = "chip " + s[1];
  }

  function time(d) {
    return d.toLocaleTimeString("ko-KR", { hour: "2-digit", minute: "2-digit", hour12: false, timeZone: "Asia/Seoul" });
  }

  function copy(text, done) {
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(text).then(function () { done(true); }, function () { done(false); });
    } else {
      done(false);
    }
  }

  /** A link-sized copy button that says 「복사됨」 beside itself for a moment, or that it could not. */
  function copyButton(button, value) {
    copy(value(), function (ok) {
      button.textContent = ok ? "복사됨" : "복사 안 됨 · 직접 선택하세요";
      button.className = "btn link small " + (ok ? "good-text" : "bad-text");
      window.setTimeout(function () { button.textContent = "복사"; button.className = "btn link small"; }, 2500);
    });
  }

  /* ── Seller Center's days ─────────────────────────────────────────────────────────────────────────── */

  function kst(d) {
    var parts = {};
    new Intl.DateTimeFormat("en-CA", { timeZone: "Asia/Seoul", year: "numeric", month: "2-digit", day: "2-digit" })
      .formatToParts(d).forEach(function (x) { parts[x.type] = x.value; });
    return parts;
  }

  /** Seller Center's own date format, 2026.10.25 — the period is typed or pasted there as it is shown here. */
  function sellerCenterDate(d) { var p = kst(d); return p.year + "." + p.month + "." + p.day; }

  /** The same day as 2026-10-25, to compare with the server's dates. */
  function isoDay(d) { var p = kst(d); return p.year + "-" + p.month + "-" + p.day; }

  function dotted(iso) { return iso.replace(/-/g, "."); }

  function daysBetween(fromIso, toIso) { return Math.round((Date.parse(toIso) - Date.parse(fromIso)) / DAY); }

  // The period to download (owner decision 2026-10-25): from the day this store's export was last uploaded to today,
  // in Seller Center's own dates (Korea). The day of the last upload is taken again on purpose — a review written
  // after that file was downloaded is in it, and a review uploaded twice changes nothing. A store with no upload yet
  // takes the longest period Seller Center offers, one year.
  function periodOf(store) {
    var now = new Date();
    var last = store.lastUploadAt ? new Date(store.lastUploadAt) : null;
    var from = last;
    if (!from) {
      from = new Date(now.getTime());
      from.setFullYear(from.getFullYear() - 1);
    }
    return {
      from: sellerCenterDate(from), to: sellerCenterDate(now), fromIso: isoDay(from), toIso: isoDay(now),
      why: last ? "마지막으로 올린 날(" + sellerCenterDate(last) + ")부터 오늘까지 · 겹치는 날은 다시 받아도 됩니다"
        : "처음 올리는 스토어라 판매자센터 최대 기간 1년"
    };
  }

  /** Uploaded today, in Korea: nothing is due for this store until tomorrow. */
  function doneToday(store) { return !!store.lastUploadAt && isoDay(new Date(store.lastUploadAt)) === isoDay(new Date()); }

  /* ── 오늘 할 일 ───────────────────────────────────────────────────────────────────────────────────── */

  var counts = { replies: 0, ready: false };

  function loadList(notice, tone) {
    say("list-notice", notice || "");
    $("list-notice").className = "msg" + (tone ? " " + tone : "");
    var states = ["APPROVED"].concat(MINE);
    return Promise.all(states.map(function (s) { return call("GET", "/v1/delegate/tasks?state=" + s); }))
      .then(function (answers) {
        var open = [];
        var mine = [];
        for (var i = 0; i < answers.length; i++) {
          if (answers[i].status === 401) { return; }
          if (answers[i].status !== 200 || !Array.isArray(answers[i].json)) { listFailed(); return; }
          if (i === 0) { open = answers[i].json; } else { mine = mine.concat(answers[i].json); }
        }
        render(open, mine);
        show("list");
      }, listFailed);
  }

  function listFailed() { loadFailed(); show("list"); }

  function loadFailed() {
    say("list-notice", "목록을 불러오지 못했습니다. 잠시 후 새로고침하세요.");
    $("list-notice").className = "msg bad-text";
  }

  function item(t, action) {
    var div = document.createElement("div");
    div.className = "item task-row";
    var left = document.createElement("div");
    left.className = "grow stack8";
    var line = document.createElement("div");
    line.className = "row";
    if (action === "open") {
      var c = document.createElement("span");
      chip(c, t.state);
      line.appendChild(c);
    }
    var stars = document.createElement("span");
    stars.className = "sm strong num-text";
    stars.textContent = "★" + t.rating;
    line.appendChild(stars);
    var m = document.createElement("span");
    m.className = "sm muted";
    m.textContent = [t.productName, t.storeLabel].filter(Boolean).join(" · ");
    line.appendChild(m);
    var body = document.createElement("p");
    body.className = "clamp";
    body.textContent = t.reviewBody || "";
    left.appendChild(line);
    left.appendChild(body);
    var b = document.createElement("button");
    b.type = "button";
    b.className = action === "open" ? "btn solid" : "btn outline";
    b.textContent = action === "open" ? "이어서 하기" : "맡기";
    b.addEventListener("click", function () { if (action === "open") { openTask(t); } else { claim(t, b); } });
    div.appendChild(left);
    div.appendChild(b);
    return div;
  }

  function render(open, mine) {
    var o = $("open");
    o.textContent = "";
    open.forEach(function (t) { o.appendChild(item(t, "claim")); });
    o.hidden = open.length === 0;
    $("open-count").textContent = String(open.length);
    $("open-label").hidden = open.length === 0 || mine.length === 0;
    var m = $("mine");
    m.textContent = "";
    mine.forEach(function (t) { m.appendChild(item(t, "open")); });
    $("mine-count").textContent = String(mine.length);
    $("mine-section").hidden = mine.length === 0;
    var total = open.length + mine.length;
    $("open-empty").hidden = total !== 0;
    $("reply-sum").textContent = total ? total + "건" : "";
    $("reply-num").className = "num " + (total ? "on" : "done");
    $("reply-num").textContent = total ? "2" : "✓";
    counts.replies = total;
    counts.ready = true;
    todo();
  }

  /** The order of the day: the step that is due is numbered in colour; a finished one is a check. */
  function todo() {
    var due = storeList.filter(function (s) { return !doneToday(s); }).length;
    $("upload-num").className = "num " + (storeList.length === 0 ? "" : due ? "on" : "done");
    $("upload-num").textContent = storeList.length && !due ? "✓" : "1";
    $("upload-sum").textContent = storeList.length === 0 ? "" : due ? "스토어 " + due + "곳" : "오늘 모두 올렸습니다";
    $("nothing").hidden = !(counts.ready && storeList.length > 0 && due === 0 && counts.replies === 0);
    $("todo-replies").hidden = counts.ready && counts.replies === 0 && due === 0 && storeList.length > 0;
  }

  function claim(t, button) {
    button.disabled = true;
    return call("POST", "/v1/delegate/tasks/" + t.id + "/claim", { stateVersion: t.stateVersion }).then(function (r) {
      if (r.status === 200 && r.json) {
        t.state = r.json.state;
        t.stateVersion = r.json.stateVersion;
        t.mine = true;
        openTask(t);
      } else if (r.status === 409 && r.json && r.json.error === "pc_check_pending") {
        loadList("판매자 PC가 이 답글을 아직 검사하지 않았습니다. 검사가 끝나면 목록에 다시 나타납니다.", "warn-text");
      } else if (r.status !== 401) {
        loadList("다른 담당자가 먼저 맡았거나 판매자가 취소했습니다. 목록을 다시 불러왔습니다.", "warn-text");
      }
    }, function () { button.disabled = false; loadFailed(); });
  }

  /* ── one task ─────────────────────────────────────────────────────────────────────────────────────── */

  function openTask(t) {
    task = t;
    check = null;
    copiedReply = false;
    say("task-error", "");
    $("copied").textContent = "";
    $("task-title").textContent = [t.productName, "★" + t.rating].filter(Boolean).join(" · ");
    chip($("task-state"), t.state);
    $("task-state").hidden = false;
    $("release").hidden = t.state !== "CLAIMED";
    $("fact-store").textContent = t.storeLabel || "";
    $("fact-no").textContent = t.reviewNo || "";
    $("fact-product").textContent = t.productName || "";
    $("fact-rating").textContent = "★" + t.rating;
    $("fact-body").textContent = t.reviewBody || "";
    $("approved-body").textContent = t.approvedBody || "";
    $("steps").hidden = false;
    $("task-done").hidden = true;
    renderSteps();
    show("task");
  }

  /** Which step is the one to do now: highlighted and open; finished ones fold to a check and a word. */
  function stepClass(id, now, done) {
    $(id).className = "st" + (now ? " now" : "") + (done ? " done" : "");
  }

  function renderSteps() {
    var unknown = task.state === "UNKNOWN";
    var clear = check != null && check.clearToPost;
    var stop = check != null && !check.clearToPost;
    $("pre-result").hidden = check == null || clear;
    $("preflight").hidden = unknown;
    $("preflight").textContent = check == null ? "지금 확인" : "다시 확인";
    // One solid per step card: once checked, the next thing to press is the copy (or the stop), not the re-check.
    $("preflight").className = check == null ? "btn solid" : "btn outline";
    $("pre-hint").hidden = check != null;
    // A clear check folds to one line — what it found and when — with the re-check beside it.
    $("st2-body").hidden = clear;
    $("pre-done-row").hidden = !clear;
    $("pre-done").textContent = clear ? "✓ 등록해도 됩니다 · " + time(check.at) + " 확인" : "";
    $("n2").className = "num " + (clear ? "done" : unknown ? "" : "on");
    $("n2").textContent = clear ? "✓" : "2";
    $("n3").className = "num " + (clear && !copiedReply ? "on" : clear ? "done" : "");
    $("n3").textContent = clear && copiedReply ? "✓" : "3";
    $("n4").className = "num " + ((clear && copiedReply) || unknown || stop ? "on" : "");
    stepClass("st2", !clear && !unknown, clear);
    stepClass("st3", clear && !copiedReply, clear && copiedReply);
    stepClass("st4", (clear && copiedReply) || unknown || stop, false);
    // The approved text opens with the check: before it, there is nothing yet that may be pasted. A task already
    // in doubt (UNKNOWN) shows it read-only, to compare with what Seller Center shows.
    $("st3-body").hidden = !(clear || unknown);
    $("st3-wait").hidden = clear || unknown;
    $("st3-wait").textContent = stop ? "이 답글은 붙여넣지 않습니다." : "확인이 끝나면 열립니다.";
    $("copy-reply").disabled = !clear;
    $("copy-reply").className = clear && !copiedReply ? "btn solid" : "btn outline";
    $("copy-reply").textContent = copiedReply ? "다시 복사" : "답글 복사";
    $("report-clear").hidden = stop;
    $("report-stop").hidden = !stop;
    $("report-posted").disabled = !(clear || unknown);
    $("report-failed").disabled = !(clear || unknown);
    $("report-unknown").disabled = !clear;
    $("report-unknown").hidden = unknown;
    $("report-posted").className = clear && copiedReply ? "btn solid" : "btn outline";
    $("stop-failed").disabled = false;
    $("stop-posted").disabled = false;
    $("stop-posted").hidden = !(stop && (check.cancelRequested || check.holdRequested));
    $("stop-failed").textContent = stop && check.holdRequested ? "등록 안 했음 · 보류 확인" : "등록하지 않고 끝내기";
    $("report-title").textContent = unknown
      ? "판매자센터에서 등록됐는지 확인한 뒤 알립니다"
      : stop ? "이 작업 끝내기" : "판매자센터 화면에서 저장된 것을 보고 알립니다";
  }

  $("preflight").addEventListener("click", function () {
    say("task-error", "");
    $("preflight").disabled = true;
    call("GET", "/v1/delegate/tasks/" + task.id + "/preflight").then(function (r) {
      $("preflight").disabled = false;
      if (r.status === 200 && r.json) {
        check = r.json;
        check.at = new Date();
        copiedReply = false;
        $("copied").textContent = "";
        task.state = r.json.state;
        chip($("task-state"), task.state);
        $("release").hidden = task.state !== "CLAIMED";
        var c = $("pre-chip");
        if (check.clearToPost) {
          c.className = "chip good";
          c.textContent = "등록해도 됩니다 · " + time(check.at) + " 확인";
          $("pre-text").textContent = "리뷰가 그대로이고, 아직 답글이 없고, 판매자가 취소하지 않았습니다.";
        } else {
          var why = [];
          if (check.cancelRequested) { why.push("판매자가 취소를 요청했습니다."); }
          if (check.holdRequested) {
            // Owner decision 2026-10-23: a held reply stops here too, and the delegate is told why.
            var promises = ((check.holdNote || {}).promises || []).join(" · ");
            var phrases = ((check.holdNote || {}).phrases || []).join(" · ");
            why.push("판매자가 이 답글의 승인을 보류했습니다." + (promises
              ? " 판매자의 기준이 바뀌어, 승인된 문장의 약속이 지금 기준과 맞지 않습니다: " + promises + "." : "")
              + (phrases ? " 판매자가 쓰지 않기로 한 표현이 들어 있습니다: " + phrases + "." : ""));
            why.push("판매자센터에 붙여 넣지 마세요. 이미 붙여 넣었다면 저장하지 말고 지우세요.");
          }
          if (check.reviewChanged) { why.push("리뷰 내용이 바뀌었습니다."); }
          if (check.displayChanged) { why.push("리뷰가 숨김 처리됐거나 전시 상태가 바뀌었습니다."); }
          if (check.alreadyReplied) { why.push("이미 답글이 있습니다."); }
          if (!why.length) { why.push("지금은 등록할 수 있는 상태가 아닙니다."); }
          c.className = "chip bad";
          c.textContent = "등록하지 마세요";
          $("pre-text").textContent = why.join(" ");
        }
        renderSteps();
      } else if (r.status !== 401) {
        check = null;
        renderSteps();
        say("task-error", "확인하지 못했습니다. 등록하지 마세요. 목록에서 다시 열어 확인하세요.");
      }
    }, function () {
      $("preflight").disabled = false;
      check = null;
      renderSteps();
      say("task-error", "서버에 연결하지 못했습니다. 확인되지 않았으니 등록하지 마세요.");
    });
  });

  $("copy-reply").addEventListener("click", function () {
    if (!(check && check.clearToPost)) { return; }
    copy(check.approvedBody, function (ok) {
      $("copied").textContent = ok ? "✓ 복사됨 · 판매자센터 답글 칸에 붙여넣고 저장하세요"
        : "복사하지 못했습니다. 위 문장을 직접 선택해 복사하세요.";
      $("copied").className = ok ? "xs good-text" : "xs bad-text";
      if (ok) { copiedReply = true; renderSteps(); }
    });
  });

  $("recheck").addEventListener("click", function () { $("preflight").click(); });

  $("copy-no").addEventListener("click", function () { copyButton($("copy-no"), function () { return task.reviewNo || ""; }); });

  // Back from Seller Center with the reply pasted: the next thing is to say what it shows. The page brings that step
  // to the eye and the keyboard — it never presses it.
  document.addEventListener("visibilitychange", function () {
    if (document.visibilityState !== "visible" || $("view-task").hidden || !$("task-done").hidden) { return; }
    if (!(check && check.clearToPost && copiedReply)) { return; }
    $("st4").scrollIntoView({ block: "center" });
    $("report-posted").focus();
  });

  // The next approved reply to offer once this one is reported; null when none is waiting.
  var next = null;

  var REPORTED = {
    POSTED: ["등록함으로 알렸습니다", "good", "판매자에게 등록됐다고 알렸습니다."],
    FAILED: ["등록하지 않음으로 알렸습니다", "neutral", "답글은 판매자에게 돌아갔습니다."],
    UNKNOWN: ["확인 필요로 남겼습니다", "warn", "판매자센터에서 등록됐는지 확인한 뒤 「내가 맡은 것」에서 다시 알려 주세요."],
    CANCELLED: ["취소로 끝냈습니다", "neutral", "판매자가 취소한 답글입니다."],
    HELD: ["보류를 확인했습니다", "neutral", "답글은 판매자에게 돌아갔습니다."]
  };

  function report(outcome) {
    say("task-error", "");
    var fingerprint = check ? check.approvedFingerprint : task.approvedFingerprint;
    var buttons = ["report-posted", "report-failed", "report-unknown", "stop-failed", "stop-posted"];
    buttons.forEach(function (id) { $(id).disabled = true; });
    call("POST", "/v1/delegate/tasks/" + task.id + "/report", {
      outcome: outcome,
      postedFingerprint: outcome === "POSTED" ? fingerprint : null,
      checkedAt: new Date().toISOString(),
      method: "SELLER_CENTER_SCREEN",
      commandId: commandId()
    }).then(function (r) {
      if (r.status === 200 && r.json) {
        reported(REPORTED[r.json.state] || ["알렸습니다", "good", ""]);
      } else if (r.status !== 401) {
        renderSteps();
        say("task-error", "알리지 못했습니다. 상태가 바뀌었을 수 있으니 목록에서 다시 여세요.");
      }
    }, function () {
      renderSteps();
      say("task-error", "서버에 연결하지 못했습니다. 아직 알려지지 않았습니다.");
    });
  }

  /** The report landed: say so, and offer the next approved reply rather than the way back to the list. */
  function reported(words) {
    $("steps").hidden = true;
    $("task-done").hidden = false;
    $("done-chip").textContent = words[0];
    $("done-chip").className = "chip " + words[1];
    $("done-text").textContent = words[2];
    $("release").hidden = true;
    $("task-state").hidden = true;
    $("next-task").hidden = true;
    $("next-none").hidden = true;
    $("to-list").className = "btn outline";
    next = null;
    call("GET", "/v1/delegate/tasks?state=APPROVED").then(function (r) {
      var open = r.status === 200 && Array.isArray(r.json) ? r.json : [];
      next = open.length ? open[0] : null;
      $("next-task").hidden = !next;
      $("next-task").textContent = "다음 답글 맡기 · " + open.length + "건 남음";
      $("next-none").hidden = !!next || r.status !== 200;
      $("to-list").className = next ? "btn outline" : "btn solid";
      if (next) { $("next-task").focus(); }
    }, function () { /* the list stays one press away */ });
  }

  $("next-task").addEventListener("click", function () { if (next) { claim(next, $("next-task")); } });
  $("to-list").addEventListener("click", function () { loadList(); });

  $("report-posted").addEventListener("click", function () { report("POSTED"); });
  $("report-failed").addEventListener("click", function () { report("FAILED"); });
  $("report-unknown").addEventListener("click", function () { report("UNKNOWN"); });
  $("stop-failed").addEventListener("click", function () { report("FAILED"); });
  $("stop-posted").addEventListener("click", function () { report("POSTED"); });

  $("release").addEventListener("click", function () {
    $("release").disabled = true;
    call("POST", "/v1/delegate/tasks/" + task.id + "/release").then(function (r) {
      $("release").disabled = false;
      if (r.status === 200) { loadList("반납했습니다. 다른 담당자가 맡을 수 있습니다."); }
      else if (r.status !== 401) { say("task-error", "반납하지 못했습니다. 목록에서 다시 여세요."); }
    }, function () { $("release").disabled = false; say("task-error", "서버에 연결하지 못했습니다."); });
  });

  /* ── review export upload ─────────────────────────────────────────────────────────────────────────── */

  function stores(list) {
    storeList = list;
    var box = $("store-list");
    box.textContent = "";
    list.forEach(function (s) {
      var done = doneToday(s);
      var row = document.createElement("div");
      row.className = "item task-row" + (openStore === s.id && !$("upload-panel").hidden ? " picked" : "");
      var left = document.createElement("div");
      left.className = "grow";
      var name = document.createElement("p");
      name.className = "strong";
      name.textContent = s.label;
      var sub = document.createElement("p");
      sub.className = "sm " + (done ? "good-text" : "muted") + " num-text";
      var p = periodOf(s);
      sub.textContent = done ? "✓ 오늘 올렸습니다 · " + time(new Date(s.lastUploadAt)) : "받을 기간 " + p.from + " ~ " + p.to;
      left.appendChild(name);
      left.appendChild(sub);
      var b = document.createElement("button");
      b.type = "button";
      b.className = done ? "btn text" : "btn outline";
      b.textContent = done ? "다시 올리기" : "올리기";
      b.setAttribute("data-store", s.id);
      b.hidden = openStore === s.id && !$("upload-panel").hidden;
      b.addEventListener("click", function () { panelClosed = false; openPanel(s.id); });
      row.appendChild(left);
      row.appendChild(b);
      box.appendChild(row);
    });
    box.hidden = list.length === 0;
    $("no-store").hidden = list.length !== 0;
    var current = list.filter(function (s) { return s.id === openStore; })[0];
    if (current) {
      if (!uploading) { fillPeriod(current); }
    } else {
      openStore = null;
      $("upload-panel").hidden = true;
      // The first store with something due opens by itself: the first thing to do is already in front of the eye.
      var due = list.filter(function (s) { return !doneToday(s); })[0];
      if (due && !panelClosed) { openPanel(due.id); }
    }
    todo();
  }

  function openPanel(id) {
    if (uploading) { return; }
    var s = storeList.filter(function (x) { return x.id === id; })[0];
    if (!s) { return; }
    openStore = id;
    resetOutcome();
    chosen = null;
    $("file").value = "";
    $("file-name").textContent = NO_FILE;
    say("upload-status", "");
    $("panel-title").textContent = s.label;
    fillPeriod(s);
    $("upload-panel").hidden = false;
    Array.prototype.forEach.call($("store-list").children, function (row) {
      var b = row.querySelector("button");
      var picked = !!b && b.getAttribute("data-store") === id;
      row.className = "item task-row" + (picked ? " picked" : "");
      if (b) { b.hidden = picked; }
    });
    pickable();
  }

  function fillPeriod(s) {
    var p = periodOf(s);
    $("from-date").textContent = p.from;
    $("to-date").textContent = p.to;
    $("period-why").textContent = p.why;
  }

  $("panel-close").addEventListener("click", function () {
    if (uploading) { return; }
    panelClosed = true;
    openStore = null;
    $("upload-panel").hidden = true;
    stores(storeList);
  });
  $("copy-from").addEventListener("click", function () { copyButton($("copy-from"), function () { return $("from-date").textContent; }); });
  $("copy-to").addEventListener("click", function () { copyButton($("copy-to"), function () { return $("to-date").textContent; }); });

  function pickable() {
    $("upload").disabled = uploading || !(chosen && openStore);
    $("file").disabled = uploading;
    $("panel-close").disabled = uploading;
  }

  function choose(file) {
    if (uploading) { return; }
    resetOutcome();
    chosen = null;
    if (!file) { $("file-name").textContent = NO_FILE; pickable(); return; }
    if (file.size > MAX_UPLOAD) {
      refuse("파일이 10MB보다 큽니다. 판매자센터에서 기간을 나눠 내려받아 올려 주세요.");
      $("file-name").textContent = NO_FILE;
      pickable();
      return;
    }
    chosen = file;
    $("file-name").textContent = file.name + " · " + Math.max(1, Math.round(file.size / 1024)).toLocaleString("ko-KR") + "KB";
    $("drop").className = "drop chosen";
    pickable();
  }

  function resetOutcome() {
    $("upload-refused").hidden = true;
    $("refused-columns").hidden = true;
    $("upload-result").hidden = true;
    $("upload-pick").hidden = false;
    $("drop").className = "drop";
  }

  function refuse(text, missing, unexpected) {
    $("refused-text").textContent = text;
    var columns = missing != null || unexpected != null;
    $("refused-columns").hidden = !columns;
    if (columns) {
      $("refused-missing").textContent = missing && missing.length ? missing.join(", ") : "없음";
      $("refused-unexpected").textContent = unexpected && unexpected.length ? unexpected.join(", ") : "없음";
    }
    $("upload-refused").hidden = false;
  }

  function refusal(status, j) {
    j = j || {};
    if (status === 400 && j.error === "not_xlsx") { refuse("엑셀(.xlsx) 파일이 아닙니다."); }
    else if (status === 422 && j.error === "header_mismatch") {
      refuse("판매자센터에서 받은 리뷰 엑셀과 열이 다릅니다. 파일을 고치지 말고 다시 내려받아 올려 주세요. "
        + "이 파일에서는 아무것도 반영하지 않았습니다.", j.missing || [], j.unexpected || []);
    } else if (status === 422 && j.error === "invalid_rows") {
      var rows = Array.isArray(j.rows) ? j.rows : [];
      var count = typeof j.rowCount === "number" ? j.rowCount : rows.length;
      var named = rows.join(", ") + "행" + (count > rows.length ? " 외 " + (count - rows.length) + "행" : "");
      refuse(count + "행을 읽지 못했습니다" + (rows.length ? ": " + named : "") + ". 이 파일에서는 아무것도 반영하지 않았습니다.");
    } else if (status === 404) {
      refuse("이 스토어는 지금 배정돼 있지 않습니다. 새로고침한 뒤 다시 고르세요.");
    } else if (status === 413) {
      refuse("파일이 10MB보다 큽니다. 판매자센터에서 기간을 나눠 내려받아 올려 주세요.");
    } else {
      refuse("올리지 못했습니다. 잠시 후 다시 시도하세요. 반영된 것은 없습니다.");
    }
  }

  function n(x) { return typeof x === "number" ? x.toLocaleString("ko-KR") : "—"; }

  // The result names the store the file went to and the period that was asked, both held from the moment it was
  // sent — never the panel as it is now.
  function uploaded(result, target) {
    $("result-what").textContent = target.label + " · " + n(result.rows) + "행";
    $("r-added").textContent = n(result.added);
    $("r-changed").textContent = n(result.changed);
    $("r-replied").textContent = n(result.replied);
    $("r-unchanged").textContent = n(result.unchanged);
    $("r-dropped").textContent = n(result.attachmentsDropped);
    $("r-dropped-line").hidden = !result.attachmentsDropped;
    var span = typeof result.writtenFrom === "string" && typeof result.writtenTo === "string";
    $("coverage").hidden = !span && result.rows !== 0;
    $("covered").textContent = span ? dotted(result.writtenFrom) + " ~ " + dotted(result.writtenTo) + " (작성일)"
      : "리뷰 없음";
    $("asked").textContent = target.period.from + " ~ " + target.period.to;
    say("short", short(result, target.period));
    $("upload-pick").hidden = true;
    $("upload-result").hidden = false;
    whoAmI();
  }

  /**
   * Whether the file looks shorter than the period that was asked. Only a hint: a day with no reviews looks the same
   * as a day left out, so it says what to look at and that it may be fine.
   */
  function short(result, period) {
    if (result.rows === 0) {
      return "파일에 리뷰가 한 건도 없습니다. 판매자센터에서 조회 기간이 " + period.from + " ~ " + period.to + " 인지 확인해 주세요.";
    }
    if (typeof result.writtenFrom !== "string" || typeof result.writtenTo !== "string") { return ""; }
    var lateStart = daysBetween(period.fromIso, result.writtenFrom) > 14;
    var earlyEnd = daysBetween(result.writtenTo, period.toIso) > 3;
    if (!lateStart && !earlyEnd) { return ""; }
    return "받을 기간보다 짧아 보입니다. 판매자센터 조회 기간이 " + period.from + " ~ " + period.to
      + " 이었는지 확인하고, 아니면 그 기간으로 다시 내려받아 올려 주세요. 그 사이에 리뷰가 없었다면 괜찮습니다.";
  }

  function stopTicking() { if (ticking) { window.clearInterval(ticking); ticking = null; } }

  // Sent with XMLHttpRequest for its measured upload progress: the percentage is bytes the browser has sent, and
  // once all are sent the clock counts the seconds the server has been reading — nothing here is a guess.
  $("upload-form").addEventListener("submit", function (e) {
    e.preventDefault();
    if (uploading || !chosen || !openStore) { return; }
    resetOutcome();
    var s = storeList.filter(function (x) { return x.id === openStore; })[0];
    var target = { id: openStore, label: s ? s.label : "", period: s ? periodOf(s) : periodOf({}) };
    var form = new FormData();
    form.append("file", chosen, chosen.name);
    uploading = true;
    pickable();
    $("upload").textContent = "올리는 중…";
    say("upload-status", "올리는 중");
    var xhr = new XMLHttpRequest();
    xhr.open("POST", "/v1/delegate/stores/" + encodeURIComponent(target.id) + "/review-uploads");
    xhr.setRequestHeader("Accept", "application/json");
    if (session) { xhr.setRequestHeader("Authorization", "Bearer " + session); }
    xhr.upload.addEventListener("progress", function (ev) {
      if (ev.lengthComputable && ev.total > 0 && !ticking) {
        say("upload-status", "올리는 중 " + Math.min(100, Math.floor(ev.loaded * 100 / ev.total)) + "%");
      }
    });
    xhr.upload.addEventListener("load", function () {
      var sent = Date.now();
      say("upload-status", "확인 중 · 0초");
      stopTicking();
      ticking = window.setInterval(function () {
        say("upload-status", "확인 중 · " + Math.floor((Date.now() - sent) / 1000) + "초");
      }, 1000);
    });
    function finished() {
      stopTicking();
      uploading = false;
      $("upload").textContent = "올리기";
      say("upload-status", "");
    }
    xhr.addEventListener("load", function () {
      finished();
      var json = null;
      try { json = xhr.responseText ? JSON.parse(xhr.responseText) : null; } catch (x) { json = null; }
      if (xhr.status === 401) { keep(null); show("login"); pickable(); return; }
      if (xhr.status === 201 && json) {
        chosen = null;
        $("file").value = "";
        $("file-name").textContent = NO_FILE;
        uploaded(json, target);
      } else {
        refusal(xhr.status, json);
      }
      pickable();
    });
    xhr.addEventListener("error", function () {
      finished();
      refuse("서버에 연결하지 못했습니다. 반영된 것은 없습니다.");
      pickable();
    });
    xhr.send(form);
  });

  $("file").addEventListener("change", function () { choose($("file").files && $("file").files[0]); });
  $("drop").addEventListener("dragover", function (e) { e.preventDefault(); if (!uploading) { $("drop").className = "drop over"; } });
  $("drop").addEventListener("dragleave", function () { $("drop").className = chosen ? "drop chosen" : "drop"; });
  $("drop").addEventListener("drop", function (e) {
    e.preventDefault();
    $("drop").className = "drop";
    choose(e.dataTransfer && e.dataTransfer.files && e.dataTransfer.files[0]);
  });
  $("upload-again").addEventListener("click", function () { if (!uploading) { resetOutcome(); } });

  $("back").addEventListener("click", function () { loadList(); });
  $("refresh").addEventListener("click", function () { whoAmI(); loadList(); });
  // The server ends this session too, so a copy of the token left anywhere stops working; the page signs out
  // whether or not that answer arrives.
  $("logout").addEventListener("click", function () {
    if (session) { call("POST", "/v1/delegate/auth/logout").then(null, function () {}); }
    keep(null);
    chosen = null;
    openStore = null;
    $("file").value = "";
    $("file-name").textContent = NO_FILE;
    $("upload-panel").hidden = true;
    resetOutcome();
    show("login");
    loginLayout();
  });

  /* ── sign in ──────────────────────────────────────────────────────────────────────────────────────── */

  // With Google on, the password form is the quiet second way: folded behind one text button.
  function loginLayout() {
    var open = !googleOn || $("login-form").getAttribute("data-open") === "1";
    $("google-login").hidden = !googleOn;
    $("app-login-title").hidden = !googleOn;
    $("login-form").hidden = !open;
    $("show-app-login").hidden = open;
    if (!$("view-login").hidden && !$("login-form").hidden && !googleOn) { $("email").focus(); }
  }

  $("show-app-login").addEventListener("click", function () {
    $("login-form").setAttribute("data-open", "1");
    $("login-form").hidden = false;
    $("show-app-login").hidden = true;
    $("email").focus();
  });

  $("use-recovery").addEventListener("click", function () {
    useRecovery = !useRecovery;
    $("second").value = "";
    $("second-label").textContent = useRecovery ? "복구 코드" : "인증 앱의 6자리 코드";
    $("second").setAttribute("inputmode", useRecovery ? "text" : "numeric");
    $("use-recovery").textContent = useRecovery ? "인증 앱 코드로 로그인" : "인증 앱을 쓸 수 없으면 복구 코드로 로그인";
  });

  $("login-form").addEventListener("submit", function (e) {
    e.preventDefault();
    say("login-error", "");
    var second = $("second").value.replace(/\s/g, "");
    var body = { email: $("email").value.trim(), password: $("password").value };
    if (useRecovery) { body.recoveryCode = second; } else { body.otp = second; }
    call("POST", "/v1/delegate/auth/login", body).then(function (r) {
      $("password").value = "";
      $("second").value = "";
      if (r.status === 200 && r.json && r.json.token) {
        keep(r.json.token);
        whoAmI();
        loadList();
      } else if (r.status === 429) { say("login-error", "로그인 시도가 많았습니다. 15분 뒤 다시 시도하세요."); }
      else if (r.status === 401 || r.status === 400) { say("login-error", "로그인 정보가 맞지 않습니다."); }
      else { say("login-error", "서버에 연결하지 못했습니다. 잠시 후 다시 시도하세요."); }
    }, function () { say("login-error", "서버에 연결하지 못했습니다. 잠시 후 다시 시도하세요."); });
  });

  /* ── Google ───────────────────────────────────────────────────────────────────────────────────────── */

  function binding() {
    var b = new Uint8Array(32);
    window.crypto.getRandomValues(b);
    return btoa(String.fromCharCode.apply(null, b)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
  }

  function googleError(key) {
    say("google-error", GOOGLE_ERRORS[key] || "서버에 연결하지 못했습니다. 잠시 후 다시 시도하세요.");
  }

  /** What the starting tab kept: the binding, and for a join its invite code. Read once and removed. */
  function takeStart() {
    var raw = null;
    try { raw = window.sessionStorage.getItem(GOOGLE_KEY); window.sessionStorage.removeItem(GOOGLE_KEY); } catch (e) { raw = null; }
    if (!raw) { return null; }
    if (raw.charAt(0) !== "{") { return { binding: raw, invite: null }; }
    try {
      var j = JSON.parse(raw);
      return typeof j.binding === "string" ? { binding: j.binding, invite: typeof j.invite === "string" ? j.invite : null } : null;
    } catch (e) { return null; }
  }

  /** Off to Google from this tab: `invite` set means a join (the same invite again), otherwise a sign-in. */
  function toGoogle(invite, button) {
    say("google-error", "");
    var b = binding();
    try {
      window.sessionStorage.setItem(GOOGLE_KEY, invite ? JSON.stringify({ binding: b, invite: invite }) : b);
    } catch (e) {
      say("google-error", "이 창에서는 구글 로그인을 마칠 수 없습니다. 비공개 창이 아닌 일반 창에서 여세요.");
      return;
    }
    button.disabled = true;
    var body = invite ? { purpose: "JOIN", inviteCode: invite, binding: b } : { purpose: "LOGIN", binding: b };
    call("POST", "/v1/delegate/auth/google/start", body).then(function (r) {
      if (r.status === 200 && r.json && typeof r.json.url === "string" && r.json.url.indexOf("https://accounts.google.com/") === 0) {
        window.location.assign(r.json.url);
      } else {
        button.disabled = false;
        googleError(r.json && r.json.error);
      }
    }, function () { button.disabled = false; googleError(null); });
  }

  $("google-signin").addEventListener("click", function () { toGoogle(null, $("google-signin")); });
  $("retry-google").addEventListener("click", function () { if (retryInvite) { toGoogle(retryInvite, $("retry-google")); } });

  /**
   * A join that came back with another Google account: the invite is still live, so name the invited address
   * (masked, from the server) and offer the same tab one press back to Google's account chooser.
   */
  function offerRetry(invite) {
    call("POST", "/v1/delegate/auth/invites/peek", { inviteCode: invite }).then(function (r) {
      if (r.status === 200 && r.json && r.json.email) {
        retryInvite = invite;
        $("retry-email").textContent = r.json.email;
        say("google-error", "");
        $("join-retry").hidden = false;
        $("google-login").hidden = true;
      }
    }, function () {});
  }

  /** Google's return: the fragment is read once and cleared; true when it was one. */
  function googleReturn() {
    var hash = window.location.hash ? window.location.hash.slice(1) : "";
    var m = /^google(-error)?=([A-Za-z0-9_-]{1,128})$/.exec(hash);
    if (!m) { return false; }
    window.history.replaceState(null, "", window.location.pathname);
    var started = takeStart();
    show("login");
    if (m[1]) {
      googleError(m[2]);
      if (m[2] === "email_mismatch" && started && started.invite) { offerRetry(started.invite); }
      return true;
    }
    if (!started) { googleError("google_failed"); return true; }
    call("POST", "/v1/delegate/auth/google/finish", { handoff: m[2], binding: started.binding }).then(function (r) {
      if (r.status === 200 && r.json && r.json.token) {
        keep(r.json.token);
        whoAmI();
        loadList();
      } else {
        var key = r.json && r.json.error;
        googleError(key);
        if (key === "email_mismatch" && started.invite) { offerRetry(started.invite); }
      }
    }, function () { googleError(null); });
    return true;
  }

  function methods() {
    return call("GET", "/v1/delegate/auth/methods").then(function (r) {
      googleOn = r.status === 200 && r.json && r.json.google === true;
      if ($("join-retry").hidden) { loginLayout(); }
    }, function () { googleOn = false; loginLayout(); });
  }

  function whoAmI() {
    call("GET", "/v1/delegate/me").then(function (r) {
      if (r.status === 200 && r.json) {
        $("who").textContent = r.json.email;
        stores(Array.isArray(r.json.stores) ? r.json.stores : []);
      }
    }, function () {});
  }

  $("today").textContent = new Date().toLocaleDateString("ko-KR",
    { month: "long", day: "numeric", weekday: "long", timeZone: "Asia/Seoul" });
  methods();
  if (googleReturn()) { /* finishing the Google trip, or saying why it did not */ }
  else if (session) { whoAmI(); loadList(); } else { show("login"); }
})();
