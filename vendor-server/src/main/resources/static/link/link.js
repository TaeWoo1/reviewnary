// The approval page for linking a seller PC — docs/vendor_server_contract_v1.md §2.
// The session token lives in this page's memory only; a reload asks for the login again.
// Every value from the server is written with textContent, never as HTML.
(function () {
  "use strict";

  var ALPHABET = /^[BCDFGHJKLMNPQRSTVWXZ23456789]{8}$/;
  var session = null;
  var account = "";
  var code = normalize(new URLSearchParams(window.location.search).get("code") || "");
  var codeFromLink = code !== "";
  if (window.location.search) {
    // The code has been read; keep it out of the address bar, history and any later copy of the URL.
    window.history.replaceState(null, "", window.location.pathname);
  }

  function $(id) { return document.getElementById(id); }

  function normalize(raw) { return String(raw).replace(/[\s-]/g, "").toUpperCase(); }

  function pretty(c) { return c.length === 8 ? c.slice(0, 4) + "-" + c.slice(4) : c; }

  function show(view) {
    ["login", "code", "confirm", "stepup", "done"].forEach(function (v) { $("view-" + v).hidden = v !== view; });
  }

  function say(id, text) { var el = $(id); el.textContent = text; el.hidden = !text; }

  function finish(tone, status, body) {
    var s = $("done-status");
    s.className = "status " + tone;
    s.textContent = status;
    $("done-body").textContent = body;
    show("done");
  }

  function call(path, body) {
    var headers = { "Content-Type": "application/json", "Accept": "application/json" };
    if (session) { headers.Authorization = "Bearer " + session; }
    return fetch(path, { method: "POST", headers: headers, body: JSON.stringify(body), credentials: "omit" })
      .then(function (r) {
        return r.text().then(function (t) {
          var json = {};
          try { json = t ? JSON.parse(t) : {}; } catch (e) { json = {}; }
          return { status: r.status, json: json };
        });
      });
  }

  function unreachable() { finish("bad", "서버에 연결하지 못했습니다.", "잠시 후 PC 화면에서 다시 시작해 주세요."); }

  function lookup() {
    call("/v1/installs/pending", { userCode: code }).then(function (r) {
      if (r.status === 401) { session = null; show("login"); return; }
      if (r.status === 404) {
        finish("bad", "코드를 찾지 못했습니다.", "코드가 만료됐거나 이미 사용됐습니다. PC 화면에서 연결을 다시 시작해 주세요.");
        return;
      }
      if (r.status !== 200) { unreachable(); return; }
      $("fact-account").textContent = account;
      $("fact-device").textContent = r.json.deviceName || "";
      var mustRetype = codeFromLink && r.json.sameNetwork !== true;
      // When the code must be typed from the PC's own screen, this page must not show it: a code that is on
      // the page can be copied from the page, and the check would prove nothing.
      $("fact-code").textContent = mustRetype ? "PC 화면에서 확인" : pretty(code);
      $("fact-code").className = mustRetype ? "" : "code";
      $("retype").hidden = !mustRetype;
      $("allow").disabled = mustRetype;
      show("confirm");
      if (mustRetype) { $("retype-input").focus(); } else { $("allow").focus(); }
    }, unreachable);
  }

  $("login-form").addEventListener("submit", function (e) {
    e.preventDefault();
    say("login-error", "");
    var email = $("email").value.trim();
    call("/v1/auth/login", { email: email, password: $("password").value }).then(function (r) {
      $("password").value = "";
      if (r.status === 200 && r.json.token) {
        session = r.json.token;
        account = email;
        if (code) { lookup(); } else { show("code"); $("code-input").focus(); }
      } else if (r.status === 429) {
        say("login-error", "로그인 시도가 많았습니다. 15분 뒤 다시 시도하세요.");
      } else if (r.status === 401 || r.status === 400) {
        say("login-error", "이메일 또는 비밀번호가 맞지 않습니다.");
      } else {
        say("login-error", "서버에 연결하지 못했습니다. 잠시 후 다시 시도하세요.");
      }
    }, function () { say("login-error", "서버에 연결하지 못했습니다. 잠시 후 다시 시도하세요."); });
  });

  $("code-form").addEventListener("submit", function (e) {
    e.preventDefault();
    var typed = normalize($("code-input").value);
    if (!ALPHABET.test(typed)) { say("code-error", "8자리 코드를 PC 화면 그대로 입력하세요."); return; }
    say("code-error", "");
    code = typed;
    codeFromLink = false;
    lookup();
  });

  $("retype-input").addEventListener("input", function () {
    $("allow").disabled = normalize($("retype-input").value) !== code;
  });

  // Linking a PC takes a code mailed to the account (§2 추가 인증): asked for here, entered below, spent by the one
  // approval that follows. The code is for this PC and this page's session only.
  var STEP_UP = {
    mail_off: "메일 발송이 아직 설정되지 않아 지금은 PC를 연결할 수 없습니다. reviewnary 운영자에게 알려 주세요.",
    mail_failed: "코드 메일을 보내지 못했습니다. 잠시 후 「코드 다시 받기」를 누르세요.",
    too_many: "코드를 너무 많이 요청했습니다. 잠시 뒤 PC 화면에서 연결을 다시 시작하세요.",
    locked: "틀린 코드가 많아 이 연결 요청을 막았습니다. 직접 요청한 연결이 아니라면 reviewnary 운영자에게 알려 주세요.",
    expired: "코드 시간이 지났습니다. 「코드 다시 받기」를 누르세요."
  };

  function sendCode() {
    say("stepup-error", "");
    call("/v1/installs/step-up", { userCode: code }).then(function (r) {
      if (r.status === 200) {
        $("stepup-to").textContent = r.json.sentTo || "";
        $("stepup-code").value = "";
        show("stepup");
        $("stepup-code").focus();
      } else if (r.status === 401) {
        session = null; show("login");
      } else if (r.status === 404) {
        finish("bad", "코드를 찾지 못했습니다.", "코드가 만료됐거나 이미 사용됐습니다. PC 화면에서 연결을 다시 시작해 주세요.");
      } else if (r.json && STEP_UP[r.json.error]) {
        show("stepup");
        say("stepup-error", STEP_UP[r.json.error]);
      } else {
        unreachable();
      }
    }, unreachable);
  }

  $("allow").addEventListener("click", function () {
    $("allow").disabled = true;
    sendCode();
  });

  $("stepup-resend").addEventListener("click", sendCode);

  $("stepup-form").addEventListener("submit", function (e) {
    e.preventDefault();
    var typed = $("stepup-code").value.replace(/\s/g, "");
    if (!/^[0-9]{6}$/.test(typed)) { say("stepup-error", "이메일의 6자리 숫자를 입력하세요."); return; }
    say("stepup-error", "");
    $("stepup-submit").disabled = true;
    call("/v1/installs/step-up/verify", { userCode: code, code: typed }).then(function (r) {
      $("stepup-submit").disabled = false;
      if (r.status === 204) { approve(); return; }
      if (r.status === 401) { session = null; show("login"); return; }
      if (r.status === 404) {
        finish("bad", "코드를 찾지 못했습니다.", "PC 연결 요청이 만료됐습니다. PC 화면에서 연결을 다시 시작해 주세요.");
        return;
      }
      var err = r.json && r.json.error;
      if (err === "wrong_code") {
        say("stepup-error", "코드가 맞지 않습니다. " + r.json.left + "번 더 틀리면 이 연결 요청을 막습니다.");
      } else if (STEP_UP[err]) {
        say("stepup-error", STEP_UP[err]);
      } else {
        unreachable();
      }
    }, function () { $("stepup-submit").disabled = false; unreachable(); });
  });

  function approve() {
    call("/v1/installs/approve", { userCode: code }).then(function (r) {
      if (r.status === 200) {
        finish("good", "PC를 연결했습니다.", "이 탭을 닫고 reviewnary 화면으로 돌아가세요. 연결은 자동으로 이어집니다.");
      } else if (r.status === 401) {
        session = null; show("login");
      } else if (r.status === 404) {
        finish("bad", "코드를 찾지 못했습니다.", "코드가 만료됐거나 이미 사용됐습니다. PC 화면에서 연결을 다시 시작해 주세요.");
      } else if (r.status === 403) {
        say("stepup-error", "이메일 코드 확인이 끝나지 않았습니다. 「코드 다시 받기」를 누르세요.");
        show("stepup");
      } else {
        unreachable();
      }
    }, unreachable);
  }

  $("cancel").addEventListener("click", function () {
    finish("", "연결을 허용하지 않았습니다.", "이 탭을 닫아도 됩니다. 요청한 코드는 10분 뒤 사라집니다.");
  });

  show("login");
  $("email").focus();
})();
