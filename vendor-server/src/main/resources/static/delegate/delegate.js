// The delegate's desk — docs/vendor_server_contract_v1.md §4.3, mockup confirmed 2026-10-21. Opened in a PC browser
// beside Seller Center: claim an approved reply, look once more just before saving, paste the exact approved text
// by hand, and report what Seller Center shows. A second tab uploads a store's review export (§3.1) as it came out
// of Seller Center. This page and this server post nothing anywhere.
//
// Copying the reply and reporting it posted open only after a clear pre-save check (`clearToPost`); a check that
// fails or cannot be read means do not post. The session token lives in this tab's sessionStorage and is gone when
// the tab closes. Every value from the server is written with textContent, never as HTML.
(function () {
  "use strict";

  var KEY = "reviewnary.delegate.session";
  var MINE = ["CLAIMED", "CANCEL_REQUESTED", "UNKNOWN"];
  var STATE = {
    APPROVED: ["승인됨", "info"],
    CLAIMED: ["등록하는 중", "info"],
    CANCEL_REQUESTED: ["판매자가 취소 요청", "warn"],
    UNKNOWN: ["확인 필요", "warn"]
  };

  var session = read();
  var task = null;
  var check = null;
  var useRecovery = false;
  var chosen = null;
  var uploading = false;
  var MAX_UPLOAD = 10 * 1024 * 1024;

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
      navigator.clipboard.writeText(text).then(done, function () { done(false); });
    } else {
      done(false);
    }
  }

  /* ── list ─────────────────────────────────────────────────────────────────────────────────────────── */

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

  function meta(t) { return [t.storeLabel, t.productName, "★" + t.rating].filter(Boolean).join(" · "); }

  function item(t, action) {
    var div = document.createElement("div");
    div.className = "item between";
    var left = document.createElement("div");
    left.className = "grow stack8";
    var line = document.createElement("div");
    line.className = "row";
    if (action === "open") {
      var c = document.createElement("span");
      chip(c, t.state);
      line.appendChild(c);
    }
    var m = document.createElement("span");
    m.className = "sm muted";
    m.textContent = meta(t);
    line.appendChild(m);
    var body = document.createElement("p");
    var text = t.reviewBody || "";
    body.textContent = text.length > 60 ? text.slice(0, 60) + "…" : text;
    left.appendChild(line);
    left.appendChild(body);
    var b = document.createElement("button");
    b.type = "button";
    b.className = action === "open" ? "btn solid" : "btn outline";
    b.textContent = action === "open" ? "열기" : "맡기";
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
    $("open-empty").hidden = open.length !== 0;
    $("open-count").textContent = String(open.length);
    var m = $("mine");
    m.textContent = "";
    mine.forEach(function (t) { m.appendChild(item(t, "open")); });
    $("mine-count").textContent = String(mine.length);
    $("mine-section").hidden = mine.length === 0;
    $("tab-count").textContent = String(open.length + mine.length);
  }

  function claim(t, button) {
    button.disabled = true;
    call("POST", "/v1/delegate/tasks/" + t.id + "/claim", { stateVersion: t.stateVersion }).then(function (r) {
      if (r.status === 200 && r.json) {
        t.state = r.json.state;
        t.stateVersion = r.json.stateVersion;
        t.mine = true;
        openTask(t);
      } else if (r.status !== 401) {
        loadList("다른 담당자가 먼저 맡았거나 판매자가 취소했습니다. 목록을 다시 불러왔습니다.", "warn-text");
      }
    }, function () { button.disabled = false; loadFailed(); });
  }

  /* ── one task ─────────────────────────────────────────────────────────────────────────────────────── */

  function openTask(t) {
    task = t;
    check = null;
    say("task-error", "");
    $("copied").textContent = "";
    $("task-title").textContent = [t.productName, "★" + t.rating].filter(Boolean).join(" · ");
    chip($("task-state"), t.state);
    $("release").hidden = t.state !== "CLAIMED";
    $("fact-store").textContent = t.storeLabel || "";
    $("fact-no").textContent = t.reviewNo || "";
    $("fact-product").textContent = t.productName || "";
    $("fact-rating").textContent = "★" + t.rating;
    $("fact-body").textContent = t.reviewBody || "";
    $("approved-body").textContent = t.approvedBody || "";
    renderSteps();
    show("task");
  }

  function renderSteps() {
    var unknown = task.state === "UNKNOWN";
    var clear = check != null && check.clearToPost;
    var stop = check != null && !check.clearToPost;
    $("pre-result").hidden = check == null;
    $("preflight").hidden = unknown;
    $("preflight").textContent = check == null ? "지금 확인" : "다시 확인";
    // One solid per step card: once checked, the next thing to press is the copy (or the stop), not the re-check.
    $("preflight").className = check == null ? "btn solid" : "btn outline";
    $("n2").className = "num " + (clear ? "done" : unknown ? "" : "on");
    $("n3").className = "num " + (clear ? "on" : "");
    $("n4").className = "num " + (clear || unknown ? "on" : "");
    $("copy-reply").disabled = !clear;
    $("copy-reply").className = clear ? "btn solid" : "btn outline";
    $("report-clear").hidden = stop;
    $("report-stop").hidden = !stop;
    $("report-posted").disabled = !(clear || unknown);
    $("report-failed").disabled = !(clear || unknown);
    $("report-unknown").disabled = !clear;
    $("report-unknown").hidden = unknown;
    $("stop-failed").disabled = false;
    $("stop-posted").disabled = false;
    $("stop-posted").hidden = !(stop && check.cancelRequested);
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
      $("copied").textContent = ok === false ? "복사하지 못했습니다. 위 문장을 직접 선택해 복사하세요." : "복사했습니다";
      $("copied").className = ok === false ? "xs bad-text" : "xs good-text";
    });
  });

  $("copy-no").addEventListener("click", function () {
    copy(task.reviewNo || "", function (ok) {
      $("copy-no").textContent = ok === false ? "복사 안 됨" : "복사함";
    });
  });

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
        var words = { POSTED: "등록함으로 알렸습니다.", FAILED: "등록하지 않음으로 알렸습니다.",
          UNKNOWN: "확인 필요로 남겼습니다. 판매자센터에서 확인한 뒤 다시 알려 주세요.", CANCELLED: "취소로 끝냈습니다." };
        loadList(words[r.json.state] || "알렸습니다.", "good-text");
      } else if (r.status !== 401) {
        renderSteps();
        say("task-error", "알리지 못했습니다. 상태가 바뀌었을 수 있으니 목록에서 다시 여세요.");
      }
    }, function () {
      renderSteps();
      say("task-error", "서버에 연결하지 못했습니다. 아직 알려지지 않았습니다.");
    });
  }

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

  function tab(name) {
    var upload = name === "upload";
    $("tab-tasks").className = upload ? "tab" : "tab on";
    $("tab-upload").className = upload ? "tab on" : "tab";
    $("tab-tasks").setAttribute("aria-selected", String(!upload));
    $("tab-upload").setAttribute("aria-selected", String(upload));
    $("pane-tasks").hidden = upload;
    $("pane-upload").hidden = !upload;
  }

  function stores(list) {
    var select = $("store");
    var before = select.value;
    select.textContent = "";
    list.forEach(function (s) {
      var o = document.createElement("option");
      o.value = s.id;
      o.textContent = s.label;
      select.appendChild(o);
    });
    if (before && list.some(function (s) { return s.id === before; })) { select.value = before; }
    select.hidden = list.length === 0;
    $("no-store").hidden = list.length !== 0;
    pickable();
  }

  function pickable() {
    $("upload").disabled = uploading || !(chosen && $("store").value);
    $("store").disabled = uploading;
    $("file").disabled = uploading;
  }

  function choose(file) {
    if (uploading) { return; }
    resetOutcome();
    chosen = null;
    if (!file) { $("file-name").textContent = ".xlsx · 10MB까지"; pickable(); return; }
    if (file.size > MAX_UPLOAD) {
      refuse("파일이 10MB보다 큽니다. 판매자센터에서 기간을 나눠 내려받아 올려 주세요.");
      $("file-name").textContent = ".xlsx · 10MB까지";
      pickable();
      return;
    }
    chosen = file;
    $("file-name").textContent = file.name + " · " + Math.max(1, Math.round(file.size / 1024)) + "KB";
    pickable();
  }

  function resetOutcome() {
    $("upload-refused").hidden = true;
    $("refused-columns").hidden = true;
    $("upload-result").hidden = true;
    $("upload-pick").hidden = false;
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

  function refusal(r) {
    var j = r.json || {};
    if (r.status === 400 && j.error === "not_xlsx") { refuse("엑셀(.xlsx) 파일이 아닙니다."); }
    else if (r.status === 422 && j.error === "header_mismatch") {
      refuse("판매자센터에서 받은 리뷰 엑셀과 열이 다릅니다. 파일을 고치지 말고 다시 내려받아 올려 주세요. "
        + "이 파일에서는 아무것도 반영하지 않았습니다.", j.missing || [], j.unexpected || []);
    } else if (r.status === 422 && j.error === "invalid_rows") {
      var rows = Array.isArray(j.rows) ? j.rows : [];
      var count = typeof j.rowCount === "number" ? j.rowCount : rows.length;
      var named = rows.join(", ") + "행" + (count > rows.length ? " 외 " + (count - rows.length) + "행" : "");
      refuse(count + "행을 읽지 못했습니다" + (rows.length ? ": " + named : "") + ". 이 파일에서는 아무것도 반영하지 않았습니다.");
    } else if (r.status === 404) {
      refuse("이 스토어는 지금 배정돼 있지 않습니다. 새로고침한 뒤 다시 고르세요.");
    } else if (r.status === 413) {
      refuse("파일이 10MB보다 큽니다. 판매자센터에서 기간을 나눠 내려받아 올려 주세요.");
    } else {
      refuse("올리지 못했습니다. 잠시 후 다시 시도하세요. 반영된 것은 없습니다.");
    }
  }

  // The result names the store the file went to, held from the moment it was sent — never the picker as it is now.
  function uploaded(result, target) {
    $("result-chip").textContent = ["올렸습니다", target.label, result.rows + "행"]
      .filter(Boolean).join(" · ");
    $("r-added").textContent = String(result.added);
    $("r-changed").textContent = String(result.changed);
    $("r-replied").textContent = String(result.replied);
    $("r-unchanged").textContent = String(result.unchanged);
    $("r-dropped").textContent = String(result.attachmentsDropped);
    $("upload-pick").hidden = true;
    $("upload-result").hidden = false;
  }

  $("upload-form").addEventListener("submit", function (e) {
    e.preventDefault();
    if (uploading || !chosen || !$("store").value) { return; }
    resetOutcome();
    var picked = $("store").options[$("store").selectedIndex];
    var target = { id: $("store").value, label: picked ? picked.textContent : "" };
    var form = new FormData();
    form.append("file", chosen, chosen.name);
    uploading = true;
    pickable();
    $("upload").textContent = "올리는 중…";
    var headers = { "Accept": "application/json" };
    if (session) { headers.Authorization = "Bearer " + session; }
    fetch("/v1/delegate/stores/" + encodeURIComponent(target.id) + "/review-uploads", {
      method: "POST", headers: headers, credentials: "omit", body: form
    }).then(function (r) {
      return r.text().then(function (t) {
        var json = null;
        try { json = t ? JSON.parse(t) : null; } catch (x) { json = null; }
        return { status: r.status, json: json };
      });
    }).then(function (r) {
      uploading = false;
      $("upload").textContent = "올리기";
      if (r.status === 401) { keep(null); show("login"); pickable(); return; }
      if (r.status === 201 && r.json) {
        chosen = null;
        $("file").value = "";
        $("file-name").textContent = ".xlsx · 10MB까지";
        uploaded(r.json, target);
      } else {
        refusal(r);
      }
      pickable();
    }, function () {
      uploading = false;
      $("upload").textContent = "올리기";
      refuse("서버에 연결하지 못했습니다. 반영된 것은 없습니다.");
      pickable();
    });
  });

  $("file").addEventListener("change", function () { choose($("file").files && $("file").files[0]); });
  $("store").addEventListener("change", function () { if (!uploading) { resetOutcome(); } pickable(); });
  $("drop").addEventListener("dragover", function (e) { e.preventDefault(); $("drop").className = "drop stack8 over"; });
  $("drop").addEventListener("dragleave", function () { $("drop").className = "drop stack8"; });
  $("drop").addEventListener("drop", function (e) {
    e.preventDefault();
    $("drop").className = "drop stack8";
    choose(e.dataTransfer && e.dataTransfer.files && e.dataTransfer.files[0]);
  });
  $("upload-again").addEventListener("click", function () { if (!uploading) { resetOutcome(); } });
  $("tab-tasks").addEventListener("click", function () { tab("tasks"); loadList(); });
  $("tab-upload").addEventListener("click", function () { tab("upload"); whoAmI(); });

  $("back").addEventListener("click", function () { loadList(); });
  $("refresh").addEventListener("click", function () { whoAmI(); loadList(); });
  // The server ends this session too, so a copy of the token left anywhere stops working; the page signs out
  // whether or not that answer arrives.
  $("logout").addEventListener("click", function () {
    if (session) { call("POST", "/v1/delegate/auth/logout").then(null, function () {}); }
    keep(null);
    chosen = null;
    $("file").value = "";
    $("file-name").textContent = ".xlsx · 10MB까지";
    resetOutcome();
    tab("tasks");
    show("login");
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

  function whoAmI() {
    call("GET", "/v1/delegate/me").then(function (r) {
      if (r.status === 200 && r.json) {
        $("who").textContent = "담당자 · " + r.json.email;
        stores(Array.isArray(r.json.stores) ? r.json.stores : []);
      }
    }, function () {});
  }

  if (session) { whoAmI(); loadList(); } else { show("login"); $("email").focus(); }
})();
