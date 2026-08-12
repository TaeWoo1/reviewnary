# tools/demo-preset — reproducible `FRESH` ↔ `CONNECTED` demo states

Two commands, so nobody has to touch the database during a demo:

```bash
tools/demo-preset/demo-preset.sh doctor      # 지금 시연 가능한가? 서비스+상태+데이터 (읽기 전용)
tools/demo-preset/demo-preset.sh fresh       # 채널 최초 연결 CTA로 다시 들어갈 수 있는 상태
tools/demo-preset/demo-preset.sh connected   # 연결된 상태 — 리뷰/문의 루틴 시연
tools/demo-preset/demo-preset.sh status      # 지금 어느 상태인지 (읽기 전용)
```

Local/dev only. A non-loopback database host, or a `prod`/`staging`-looking `APP_ENV` /
`SPRING_PROFILES_ACTIVE` / `SELLEROPS_ENV`, fails closed before a single statement runs.

---

## 1. Why the first-connection CTA disappears on an existing account

Three places decide this, and only the third one is about credentials:

| Layer | Code | Behaviour |
|---|---|---|
| Backend catalog read | `ChannelService.listForOrg` | If the org has **any** seller account on the channel, the card's `status` and `actionLabel` come from **the account**, not the catalog row. |
| Frontend card action | `lib/channelConnection.ts` → `channelCardAction` | With an account present the intent is `manage` (or `reconnect` for Cafe24) — it routes to `/connect/channels/:accountId`. The `connect-coupang` / `connect-naver` intents are only reachable on the **no-account** branch. |
| Connect page landing | `lib/coupangTutorial.ts` → `resolvePhase` | `issuance` (the WING key walkthrough — the true first step) is returned **only** when there is no account *and* no stored credential. |

So a single row in `seller_accounts` — even a `PENDING` one with no credential, which is what the
dev seeder (`MockDataSeeder`) leaves behind for COUPANG and NAVER — is enough to make the first
connection un-demoable. Deleting the credential is **not** enough; deleting the account row is what
matters.

One more trap: with no account, the card falls back to the **catalog** `channels.status`, and the
seeder ships COUPANG as `CONNECTED`, whose action label is `관리`. `fresh` therefore also lowers that
catalog row to `AVAILABLE` so the button reads `연결하기`. `connected` puts the original value back.

## 2. The two states

**`FRESH`** — the demo org has never connected this channel.

Archived, then removed, all scoped to *(demo org × this channel)*:
`seller_accounts` (the API-mode row) · `connector_credentials` · `channel_connection_status` ·
`connector_alerts` · `sync_schedules` · `account_session_slot` · `sync_jobs` · `sync_cursors`,
plus `channels.status → AVAILABLE`.

**Never touched:** `reviews`, `inquiries`, `products`, `order_daily_summaries`, review/inquiry work
queues, triage, drafts, approvals, issue memory — every other org and every other channel. The org's
content-row counts are read before and after each run and must match exactly, or the script aborts.

**Refuses instead of deleting:** if the account owns `inquiries`, `inquiry_work_item`,
`inquiry_import_batch`, `inquiry_work_item_dismissal_batch`, `cafe24_oauth_state`,
`review_import_launch`, `review_import_plan`, or (unless `--include-collected-orders`)
`channel_orders`, `fresh` stops and prints the counts.

**`CONNECTED`** — the snapshotted baseline, restored row-for-row under the original primary keys
(`connected` is idempotent; re-running it is a no-op). The routine review/inquiry demo does not
depend on the Coupang row at all — it runs on the org's existing NAVER reviews and Cafe24 inquiries,
which `fresh` never touches. Restoring simply puts the channel card back where it was.

> The baseline is whatever the channel looked like when the snapshot was taken — on the current dev
> database that is Coupang at `PENDING` with no credential. After a **real** first connection you can
> promote that to the baseline with `demo-preset.sh snapshot`, and `connected` will then restore a
> genuinely connected Coupang. The script never fabricates a `CONNECTED` status or a fake credential.

### Where the archive lives

Inside the database, in a dev-only `demo_preset` schema created on demand (`preset-lib.sql`) — not a
Flyway migration, and `public` is never altered. Keeping it in SQL is deliberate: the encrypted
credential row is archived and restored **by a single SQL statement**, so ciphertext never passes
through the shell, a log line, or a file. The script only ever reads credential state as a yes/no.

One archive slot per *(org, channel)*. `fresh` takes a snapshot automatically when none exists, and
**refuses** to clear when the live state carries something the snapshot does not (a different
account, or a credential the archive lacks — exactly what a real live connection produces).
`--resnapshot` is the deliberate "make the current state the new baseline" answer.

## 2b. `doctor` — run it once, just before the demo

Read-only; it does not even create the archive schema.

| Section | What it checks |
|---|---|
| services | backend `/health`, frontend, **local agent bridge** (`/bridge/health` — the Coupang key-issuance walkthrough needs it), agent runtime (`/agent` page only, not needed today) |
| backend ↔ this database | whether the running backend really serves **this** database, by comparing the channel card from `/api/channels` with the row in the DB. Needs a login, so it is opt-in: without `SELLEROPS_DEMO_PASSWORD` it reports `MANUAL` rather than embedding a password in the tool |
| demo state | whether you are in `FRESH`, and whether a snapshot exists so `connected` can work |
| routine demo data | NAVER account state + review count, Cafe24 account state + inquiry count |
| CHECK MANUALLY | §4 below — the marketplace-side facts SellerOps genuinely cannot observe. They are printed as unknowns, never guessed |

Exit code 1 if any automatically-checkable item fails.

## 3. Today's demo order

```bash
# once, before the audience arrives
tools/demo-preset/demo-preset.sh doctor          # services + demo state + routine data, one screen

# A. 최초 연결 데모
tools/demo-preset/demo-preset.sh fresh
#   → /connect  : 쿠팡 카드가 '연결하기'
#   → 클릭      : /connect/coupang, 1단계 'API 키 발급' 워크스루부터 시작
#   → (live WING key issuance는 §4 checklist + 별도 승인 필요)

# B. 루틴 데모로 복귀
tools/demo-preset/demo-preset.sh connected
#   → /connect  : 쿠팡 계정이 되돌아옴
#   → /inbox, /memory, /reports : 기존 리뷰·문의 데이터로 그대로 시연
```

`fresh` and `connected` can be run in either order, any number of times. Both print before/after
state and their own verification lines; a failed check aborts loudly instead of leaving a half state.

The frontend and backend read this state per request — no restart is needed between switches. Reload
the browser tab (the connect page derives its phase from the server on load).

## 4. Coupang: the SellerOps database is **not** the marketplace

`fresh` resets SellerOps' side only. The seller's **WING Open API key lives at Coupang** and nothing
in this repo deletes, re-issues, or reads it. After `fresh` the product will honestly show "not
connected" while the marketplace may still hold a perfectly valid key.

That matters for the demo: `/connect/coupang` step 1 is the guided **key issuance** walkthrough. If
the WING account already has a self-developed Open API key, the issuance screen shows the existing
key instead of the issue-a-new-one flow, and the "first issuance" story does not play.

### Marketplace-side checklist (operator-only; no script can verify these)

Do these in WING, before the demo, on the account you will demo with:

- [ ] Decide which story you are telling — **new key issuance** (needs no existing key) or
      **"이미 키가 있어요" → credential entry** (needs the key and its Secret in hand).
- [ ] For new issuance: confirm no self-developed Open API key exists on the WING account. Deleting an
      existing key is a **destructive marketplace action** — see
      `docs/coupang_wing_key_deletion_live_v1.md`, and note the Secret Key of a deleted key cannot be
      recovered. Do it well before the demo, never live.
- [ ] Have the WING login ready and already signed in in the browser profile the Local Agent uses —
      **no CAPTCHA/2FA bypass exists or is attempted**; a login challenge stops the walkthrough.
- [ ] 주문 API 그룹 권한 granted on the application (otherwise the connection test fails with the
      order-access reason code).
- [ ] The calling machine's **public egress IP registered** in the app's API 호출 IP allowlist —
      an unregistered IP returns `403 Not allowed IP` on the first probe.
- [ ] Know the key's **유효기간**, if you plan to show the expiry/renewal surface.
- [ ] Any **live** Coupang call still needs a fresh, single-use, in-turn approval per
      `docs/sellerops_live_approval_contract.md` and the `tools/coupang-local` harness. A demo audience
      is not an approval, and this preset grants nothing.

Reaching the credential form and stopping there needs none of the above — only a live key issuance or
a live connection test does.

## 5. Options

```
--channel CODE                 channel to reset (default COUPANG, or SELLEROPS_DEMO_CHANNEL).
                               NAVER works identically; Cafe24's OAuth state is a blocking table by design.
--resnapshot                   make the CURRENT state the baseline before clearing
--include-collected-orders     also archive+clear the account's collected orders — needed to re-run
                               `fresh` after a real sync; off by default, so `fresh` stops instead

SELLEROPS_DEMO_PGHOST/PGPORT/PGUSER/PGDATABASE/PGPASSWORD   database (loopback host only)
SELLEROPS_DEMO_USER_EMAIL                                   demo login that identifies the org
                                                            (default demo@sellerops.ai)
SELLEROPS_DEMO_PASSWORD                                     doctor only, optional — log in and prove the
                                                            running backend serves THIS database
SELLEROPS_DEMO_BACKEND_URL / _FRONTEND_URL / _BRIDGE_URL / _AGENT_RUNTIME_URL
                                                            doctor probe targets (loopback defaults)
```
