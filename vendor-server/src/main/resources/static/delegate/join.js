// A delegate accepts an invite — docs/vendor_server_contract_v1.md §2, mockup confirmed 2026-10-21. Password → the
// authenticator (the account exists only once its first code matches) → ten recovery codes shown once.
//
// The invite code arrives in the link's fragment (/delegate/join#…), which no browser sends to a server. It is read
// once, kept in this page's memory, and cleared from the address bar at once — a reload or a shared screenshot does
// not carry it. The session from the last step goes to this tab's sessionStorage under the desk's key, so the desk
// opens signed in. Every value from the server is written with textContent or drawn, never as HTML.
(function () {
  "use strict";

  var DESK_KEY = "reviewnary.delegate.session";
  var MIN_PASSWORD = 10;
  var ERRORS = {
    invalid_invite: "초대 링크가 맞지 않거나 이미 썼거나 기한이 지났습니다. 초대한 사람에게 새 링크를 받으세요.",
    weak_password: "비밀번호는 10자 이상이어야 합니다.",
    already_registered: "이미 등록된 담당자입니다. 로그인 화면에서 로그인하세요.",
    wrong_code: "인증 앱의 코드가 맞지 않습니다. 휴대폰 시계가 자동으로 맞춰져 있는지 확인하고 새 코드를 넣으세요.",
    not_staged: "처음 단계부터 다시 해 주세요."
  };

  var code = null;
  var password = null;
  var token = null;
  var recoveryCodes = [];

  function $(id) { return document.getElementById(id); }

  function say(id, text) { var el = $(id); el.textContent = text || ""; el.hidden = !text; }

  function show(view) {
    ["none", "password", "totp", "codes"].forEach(function (v) { $("view-" + v).hidden = v !== view; });
    var step = { password: 1, totp: 2, codes: 3 }[view] || 0;
    [1, 2, 3].forEach(function (n) { $("s" + n).className = n === step ? "on" : ""; });
    window.scrollTo(0, 0);
  }

  function post(path, body) {
    return fetch(path, {
      method: "POST",
      headers: { "Accept": "application/json", "Content-Type": "application/json" },
      credentials: "omit",
      body: JSON.stringify(body)
    }).then(function (r) {
      return r.text().then(function (t) {
        var json = null;
        try { json = t ? JSON.parse(t) : null; } catch (e) { json = null; }
        return { status: r.status, json: json };
      });
    });
  }

  function failure(r) {
    var key = r.json && r.json.error;
    return ERRORS[key] || "서버에 연결하지 못했습니다. 잠시 후 다시 시도하세요.";
  }

  /* ── 1. password ──────────────────────────────────────────────────────────────────────────────────── */

  $("password-form").addEventListener("submit", function (e) {
    e.preventDefault();
    say("password-error", "");
    var p1 = $("password").value;
    var p2 = $("password2").value;
    if (p1.length < MIN_PASSWORD) { say("password-error", ERRORS.weak_password); return; }
    if (p1 !== p2) { say("password-error", "두 비밀번호가 다릅니다."); return; }
    stage(p1, "password-next", "password-error");
  });

  function stage(p, buttonId, errorId) {
    $(buttonId).disabled = true;
    post("/v1/delegate/auth/invites/stage", { inviteCode: code, password: p }).then(function (r) {
      $(buttonId).disabled = false;
      if (r.status === 200 && r.json && r.json.secret && Array.isArray(r.json.qr)) {
        password = p;
        $("password").value = "";
        $("password2").value = "";
        $("secret").textContent = r.json.secret.replace(/(.{4})(?=.)/g, "$1 ");
        draw(r.json.qr);
        $("otp").value = "";
        say("totp-error", "");
        show("totp");
        $("otp").focus();
      } else {
        say(errorId, failure(r));
      }
    }, function () {
      $(buttonId).disabled = false;
      say(errorId, "서버에 연결하지 못했습니다. 잠시 후 다시 시도하세요.");
    });
  }

  /** The server's QR rows ('1' dark), four pixels a module, with a four-module quiet zone. */
  function draw(rows) {
    var canvas = $("qr");
    var scale = 4;
    var offset = 4 * scale;
    canvas.width = (rows.length + 8) * scale;
    canvas.height = canvas.width;
    var ctx = canvas.getContext("2d");
    ctx.fillStyle = "#FFFFFF";
    ctx.fillRect(0, 0, canvas.width, canvas.height);
    ctx.fillStyle = "#191F28";
    for (var y = 0; y < rows.length; y++) {
      for (var x = 0; x < rows[y].length; x++) {
        if (rows[y].charAt(x) === "1") { ctx.fillRect(offset + x * scale, offset + y * scale, scale, scale); }
      }
    }
  }

  /* ── 2. authenticator ─────────────────────────────────────────────────────────────────────────────── */

  $("totp-form").addEventListener("submit", function (e) {
    e.preventDefault();
    say("totp-error", "");
    var otp = $("otp").value.replace(/\s/g, "");
    if (!/^\d{6}$/.test(otp)) { say("totp-error", "인증 앱에 나온 6자리 숫자를 넣으세요."); return; }
    $("totp-confirm").disabled = true;
    post("/v1/delegate/auth/invites/confirm", { inviteCode: code, otp: otp }).then(function (r) {
      $("totp-confirm").disabled = false;
      if (r.status === 201 && r.json && r.json.token && Array.isArray(r.json.recoveryCodes)) {
        token = r.json.token;
        recoveryCodes = r.json.recoveryCodes;
        code = null;
        password = null;
        showCodes();
      } else {
        $("otp").value = "";
        say("totp-error", failure(r));
        if (r.json && r.json.error === "not_staged") { show("password"); }
      }
    }, function () {
      $("totp-confirm").disabled = false;
      say("totp-error", "서버에 연결하지 못했습니다. 잠시 후 다시 시도하세요.");
    });
  });

  // Staging again gives a new secret and spends nothing: the QR from before stops working.
  $("restart").addEventListener("click", function () {
    say("totp-error", "");
    if (password) { stage(password, "restart", "totp-error"); } else { show("password"); }
  });

  /* ── 3. recovery codes ────────────────────────────────────────────────────────────────────────────── */

  function showCodes() {
    var box = $("codes");
    box.textContent = "";
    recoveryCodes.forEach(function (c) {
      var s = document.createElement("span");
      s.textContent = c;
      box.appendChild(s);
    });
    $("saved").checked = false;
    $("to-desk").disabled = true;
    show("codes");
  }

  $("copy-codes").addEventListener("click", function () {
    var text = recoveryCodes.join("\n");
    var done = function (ok) {
      $("copied").textContent = ok ? "복사했습니다" : "복사하지 못했습니다. 직접 적어 두세요.";
      $("copied").className = ok ? "xs good-text" : "xs bad-text";
    };
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(text).then(function () { done(true); }, function () { done(false); });
    } else {
      done(false);
    }
  });

  $("print-codes").addEventListener("click", function () { window.print(); });

  $("saved").addEventListener("change", function () { $("to-desk").disabled = !$("saved").checked; });

  $("to-desk").addEventListener("click", function () {
    if (!$("saved").checked || !token) { return; }
    try { window.sessionStorage.setItem(DESK_KEY, token); } catch (e) { /* the desk asks to sign in instead */ }
    token = null;
    recoveryCodes = [];
    window.location.replace("/delegate");
  });

  /* ── start ────────────────────────────────────────────────────────────────────────────────────────── */

  // Also on hashchange: opening the link in a tab already on this page changes only the fragment, and the page
  // is not loaded again.
  function start() {
    var hash = window.location.hash ? window.location.hash.slice(1) : "";
    if (window.location.hash) {
      window.history.replaceState(null, "", window.location.pathname);
    }
    if (/^[A-Za-z0-9_-]{20,128}$/.test(hash)) {
      code = hash;
      password = null;
      say("password-error", "");
      show("password");
      $("password").focus();
    } else if (code == null) {
      show("none");
    }
  }

  window.addEventListener("hashchange", start);
  start();
})();
