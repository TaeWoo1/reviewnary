# NAVER + Cafe24 Cloud Shadow Run v1 — procedure

> **Scope.** One Korea-region VM with a fixed EIP runs the whole product and the NAVER review export
> agent. Sources: NAVER INQUIRY (API) · NAVER REVIEW (official XLSX, cloud browser agent) · Cafe24
> INQUIRY (API) · Cafe24 REVIEW (API). **Coupang is excluded by not connecting it.** A new empty cloud
> database. Marketplace WRITE off everywhere. Measurement starts at a T0 snapshot taken after the first
> settle, and runs 72h.
>
> **This document is a procedure, not a new design.** Everything it names exists: the pilot compose
> overlay, the review-import plan/segment/launch model, the canonical importer, the helper-device token,
> the responsibility runtime. The only new code is the narrow unattended-launch authority
> (`sellerops.review-import.unattended.*`) and the agent in `tools/naver-review-cloud-agent/`.

## 0. Why Coupang needs no switch

`ResponsibilityTemplate.CUSTOMER_OPERATIONS_V1` lists Coupang INQUIRY, but a template source is a
**candidate**, not an obligation: `ResponsibilitySources.resolve` makes it a source only where the
organisation holds a non-file-upload seller account on that channel. No Coupang account ⇒ no source, no
observation, no gap case, and nothing to configure.

**Do not try to exclude it with the connector flag.** `collectable()` answers `true` when a channel has
no collector at all (`orElse(true)`) — «this deployment wired no collector» is not the same statement as
«the collector says it cannot read this». With `SELLEROPS_CONNECTOR_COUPANG_ENABLED=false` **and** a
Coupang account in the database, the source stays required and every window records
`CONNECTOR_UNAVAILABLE`. A clean database is what makes the exclusion structural.

NAVER REVIEW and Coupang REVIEW are not required sources either way — they are `deviceRecipes()`. So a
review-export cycle that fails **cannot fail a responsibility window**. That is this run's safety margin.

## 1. Host and network (one VM)

| | |
|---|---|
| region | `ap-northeast-2` (Seoul) |
| address | one Elastic IP; DNS **A** record → it; `PILOT_PUBLIC_HOST` is that bare name |
| open ports | 80 / 443 only. The pilot overlay's `ports: !reset []` removes every raw port; Caddy is the only listener |
| egress | `deploy/pilot/egress-check.sh` must print `ok: actual == advertised` — it compares the host's outbound address, the **backend container's** outbound address, and `SELLEROPS_CONNECTOR_NAVER_ADVERTISED_EGRESS_IPS` |

**One VM, one egress address, one IP to register.** Splitting the browser agent onto a second host would
give the review export a different source address than the API calls, and the seller centre would see a
sign-in from an address that no NAVER app setting covers.

## 2. External registration (people, not scripts)

1. **NAVER commerce app** → 「API 호출 IP」 = the EIP. Then `SELLEROPS_CONNECTOR_NAVER_ADVERTISED_EGRESS_IPS`
   = the same value, and only then `SELLEROPS_CONNECTOR_NAVER_ENABLED=true`.
2. **Cafe24 Developers app** → redirect URI **byte-identical** to
   `https://<PILOT_PUBLIC_HOST>/api/connect/cafe24/callback` (the overlay derives exactly that, and the
   token exchange reads the same property). `mall.write_community` re-consent is **not** needed: this run
   reads.
3. **S3 bucket + access key** — off-host backup is fail-closed; a deploy without it is refused.
4. Secrets generated **on the host**: `SELLEROPS_JWT_SECRET` (≥32 chars), `SELLEROPS_VAULT_MASTER_KEY`.

> **Known unverified vendor behaviour.** Whether a second OAuth consent for the same `(app, mall)` keeps
> the existing refresh token alive is not provable in this repository. If it does not, the local Demo
> Org's Cafe24 connection breaks. That is a reason to treat the local database as the thing being left
> alone — see §3.

## 3. Clean bootstrap — a new database, and the deploy passes that shape requires

A **new empty volume**, not a copy of the local database:

- `deploy.sh` forces `SELLEROPS_FLYWAY_BASELINE_ON_MIGRATE=false` — a non-empty schema with no history
  must fail the boot rather than be assumed current. 113 migrations apply cleanly to an empty volume.
- `restore.sh` requires **the master key that sealed the dump**; a different key opens nothing.
- A copied dump's sealed NAVER credentials were used from a different egress address and its Cafe24
  tokens were issued against a different redirect, so both would need re-connecting anyway.
- `deploy.sh` refuses seed and mock on a pilot host (`SEED_ENABLED`, `SEED_DEMO_CONTENT`,
  `CONNECTOR_MOCK_ENABLED`, `CONNECTOR_MOCK_FALLBACK_ENABLED` all false), so a pilot database has no
  `DEMO_SEED` rows to separate later.

**Honest cost:** with no history, `firstSettledObservation` is null, so the first window's candidate scan
reaches back `now − ACQUISITION_REACH` (15 days), not «everything». Repeated issues, drafts and decision
history start at zero. That is what a shadow run measures from.

### Pass 1 — the host exists, nothing is named yet

```
SELLEROPS_REVIEW_IMPORT_UNATTENDED_ENABLED=false
SELLEROPS_RESPONSIBILITY_SCHEDULER_ENABLED=false
RESPONSIBILITY_RUNTIME_ORG_IDS=
```
`preflight.sh` → `deploy.sh` → `smoke.sh`. Then, in the browser: sign up, connect **NAVER** and
**Cafe24**, connect **no Coupang**. Read the organisation UUID.

### Pass 2 — pair the agent, then name both ids

On the VM, in `tools/naver-review-cloud-agent/`:

```
POST /api/auth/device/code                     -> userCode          (the agent asks)
POST /api/helper-devices/approve {userCode}     (the operator, from their own browser, JWT session)
POST /api/auth/device/token                    -> rvh_…             (the agent stores it in .env)
GET  /api/helper-devices                       -> the device UUID   (the operator reads it)
```

Then set all five and deploy again:

```
SELLEROPS_RESPONSIBILITY_SCHEDULER_ENABLED=true
RESPONSIBILITY_RUNTIME_ORG_IDS=<org uuid>
SELLEROPS_REVIEW_IMPORT_UNATTENDED_ENABLED=true
SELLEROPS_REVIEW_IMPORT_UNATTENDED_ORG_IDS=<org uuid>
SELLEROPS_REVIEW_IMPORT_UNATTENDED_DEVICE_IDS=<device uuid>
```

`deploy.sh` refuses `ENABLED=true` with either list blank, and refuses `*` in either. It also enforces
the responsibility pair (`RESPONSIBILITY_RUNTIME_ORG_IDS` named ⇒ scheduler true).

**The collect scheduler and self-pilot stay off.** `ResponsibilitySourceObserver` runs
`SyncRunExecutor.execute` itself for every source in every window — the responsibility runtime does its
own collection, and turning self-pilot on would additionally require the collect poller (deploy.sh
enforces that pair too) for no gain here.

**Marketplace WRITE stays off**: `SELLEROPS_INQUIRY_PUBLISH_EXECUTION_ENABLED=false`, and the review
reply lane has no proven WRITE adapter to enable.

## 4. The review export agent on this host

```
sudo deploy/pilot/install-review-export-agent.sh          # systemd unit, Restart=no, under xvfb
sudo -u reviewnary-agent … -m agent.run login             # YOU sign in, once, on a visible screen
sudo -u reviewnary-agent … -m agent.run once              # prove one full cycle
systemctl start reviewnary-review-export                  # the 72h window
```

It is deliberately **not** a compose service: it needs a persistent browser profile that a rebuild would
lose, a one-time human sign-in that a detached container cannot show, and `Restart=no` — a cycle that
ends `AUTH_REQUIRED` must wait for a person, not be restarted into the same wall.

`PILOT_GUIDED_HELPER_ENABLED` stays **false**. That flag is about the *seller's own* machine (deploy.sh
requires its bridge URL to be loopback); this agent talks to the product over HTTPS like any other
paired helper and needs no browser bridge.

## 5. T0 and what the 72h measures

Take T0 **after the first settle**, not at deploy: the first responsibility window and the first export
cycle are the load, not the measurement.

T0 snapshot (read-only):

```sql
select data_type, count(*) from reviews  where org_id = :org group by 1;
select count(*) from inquiries where org_id = :org;
select channel_code, data_type, status, count(*) from sync_jobs where org_id = :org group by 1,2,3;
select count(*) from review_import_segment_attempt where org_id = :org and status = 'SUCCEEDED';
```

Plus `deploy/pilot/backup.sh` once, so T0 has a restore point.

During the run, the honest readings are: responsibility windows worked vs windows with a source gap,
`review_import_segment_attempt` SUCCEEDED count and `rows_new` / `rows_duplicate`, the agent's own
`.runs/*.json` verdict per cycle, and — because this is a shadow run — **zero** rows in
`inquiry_execution`, `review_reply_execution` and any approval table.

## 6. What stops the run, and what does not restart it

The agent stops on the first non-`INGESTED` verdict and does not retry by relaxation: no stealth, no
CAPTCHA solving, no widened `allowed_domains`, no loosened fence.

| verdict | meaning |
|---|---|
| `AUTH_REQUIRED` | NAVER wants a person. Sign in again with `agent.run login`, then restart the unit. |
| `STORE_UNRESOLVED` | no stable store identifier was readable — PD-4 says stop rather than ingest against a guess |
| `EXPORT_CONTROL_NOT_FOUND` · `UNSUPPORTED_STATE` | the screen was not what the task describes |
| `NO_LAUNCH` | the product refused to authorize a run (capability off, ids not named, no remaining segment, two NAVER accounts) |
| `AMBIGUOUS_FILES` | more than one download appeared; nothing is ingested |
| `ARTIFACT_INVALID` | the bytes are neither OOXML nor delimited text |
| `INGEST_REFUSED` | the server refused; the raw file stays for inspection and is **not** deleted |

## 7. Not in this run

Scheduled unattended execution as a *product* posture is still **Q-1 OPEN**
(`docs/review_acquisition_aside_v2.md` §12): this run is a bounded, approved experiment for one named
organisation and one named device, not a decision that it may ship. The Aside device lane is not used
here, and its missing compose/pilot env plumbing (`SELLEROPS_RESPONSIBILITY_ASIDE_*`) is left unfixed on
purpose — this path does not read it.
