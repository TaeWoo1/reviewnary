// A seller accepts an invite — docs/vendor_server_contract_v1.md §2, mockup confirmed 2026-10-22 with one addition:
// the invited address is shown before anything is decided, so nobody makes an account that is not theirs. Then a
// password, then the two next steps (link the PC, open the phone page).
//
// The invite code arrives in the link's fragment (/join#…), which no browser sends to a server. It is read once, kept
// in this page's memory, and cleared from the address bar at once. The new session goes to this tab's sessionStorage
// under the phone page's key only when the seller opens that page here. Every value from the server is written with
// textContent or drawn, never as HTML.
(function () {
  "use strict";

  var REPLIES_KEY = "reviewnary.replies.session";
  var MIN_PASSWORD = 10;
  var OFFLINE = "서버에 연결하지 못했습니다. 잠시 후 다시 시도하세요.";
  var ERRORS = {
    invalid_invite: "이 초대 링크는 쓸 수 없습니다. 이미 썼거나 7일이 지났습니다. 초대한 분께 새 링크를 받으세요.",
    weak_password: "비밀번호는 10자 이상이어야 합니다.",
    already_registered: "이미 가입된 계정입니다. 휴대폰 승인 화면에서 로그인하세요."
  };

  var code = null;
  var token = null;

  function $(id) { return document.getElementById(id); }

  function say(id, text) { var el = $(id); el.textContent = text || ""; el.hidden = !text; }

  function show(view) {
    ["none", "password", "done"].forEach(function (v) { $("view-" + v).hidden = v !== view; });
    $("s1").className = view === "password" ? "on" : "";
    $("s2").className = view === "done" ? "on" : "";
    window.scrollTo(0, 0);
  }

  function none(text) { $("none-text").textContent = text; show("none"); }

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

  function failure(r) { return ERRORS[r.json && r.json.error] || OFFLINE; }

  /* ── 1. whose invite, then a password ─────────────────────────────────────────────────────────────── */

  function peek() {
    post("/v1/auth/invites/peek", { inviteCode: code }).then(function (r) {
      if (r.status === 200 && r.json && r.json.email) {
        $("invited").textContent = r.json.email;
        if (r.json.recovery === true) {
          // A recovery invite: the same account, a new password — never a second account.
          $("password-title").textContent = "reviewnary 계정 복구";
          $("invited-label").textContent = "복구할 계정";
          $("recovery-note").hidden = false;
          $("join").textContent = "새 비밀번호로 정하기";
          $("done-title").textContent = "비밀번호를 새로 정했습니다";
        }
        say("password-error", "");
        show("password");
        $("password").focus();
      } else {
        code = null;
        none(failure(r));
      }
    }, function () { none(OFFLINE); });
  }

  $("password-form").addEventListener("submit", function (e) {
    e.preventDefault();
    say("password-error", "");
    var p1 = $("password").value;
    var p2 = $("password2").value;
    if (p1.length < MIN_PASSWORD) { say("password-error", ERRORS.weak_password); return; }
    if (p1 !== p2) { say("password-error", "두 비밀번호가 다릅니다."); return; }
    $("join").disabled = true;
    post("/v1/auth/invites/redeem", { inviteCode: code, password: p1 }).then(function (r) {
      $("join").disabled = false;
      if (r.status === 201 && r.json && r.json.token && Array.isArray(r.json.phoneQr)) {
        code = null;
        token = r.json.token;
        $("password").value = "";
        $("password2").value = "";
        $("done-email").textContent = r.json.email || $("invited").textContent;
        $("phone-url").textContent = String(r.json.phoneUrl || "").replace(/^https?:\/\//, "");
        draw(r.json.phoneQr);
        show("done");
      } else {
        say("password-error", failure(r));
      }
    }, function () {
      $("join").disabled = false;
      say("password-error", OFFLINE);
    });
  });

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

  /* ── 2. next steps ────────────────────────────────────────────────────────────────────────────────── */

  $("open-replies").addEventListener("click", function () {
    if (token) {
      try { window.sessionStorage.setItem(REPLIES_KEY, token); } catch (e) { /* the page asks to sign in instead */ }
    }
    token = null;
    window.location.assign("/replies");
  });

  /* ── start ────────────────────────────────────────────────────────────────────────────────────────── */

  // Also on hashchange: opening the link in a tab already on this page changes only the fragment.
  function start() {
    var hash = window.location.hash ? window.location.hash.slice(1) : "";
    if (window.location.hash) {
      window.history.replaceState(null, "", window.location.pathname);
    }
    if (/^[A-Za-z0-9_-]{20,128}$/.test(hash)) {
      code = hash;
      peek();
    } else if (code == null && token == null) {
      none("초대 링크가 없습니다. 받은 링크를 그대로 열어 주세요. 이 화면을 새로고침했다면 링크를 다시 여세요.");
    }
  }

  window.addEventListener("hashchange", start);
  start();
})();
