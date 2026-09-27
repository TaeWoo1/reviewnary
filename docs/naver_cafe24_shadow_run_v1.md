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

## 5-A. Shadow monitoring — fail-fast observation, not recovery

`deploy/pilot/shadow-watch.sh` + a systemd **oneshot and 5-minute timer** +
**CloudWatch custom metrics**. It reads, emits numbers and exits. Nothing in it restarts a service, rolls
anything back, retries a marketplace call or touches a model — a red metric is a person's decision, and
the alarms exist so a person is told.

**No CloudWatch Agent.** The observer emits with `aws cloudwatch put-metric-data`, so the host needs one
IAM action and no extra daemon.

### The metrics

| metric | meaning | alarm |
|---|---|---|
| `ShadowWatchHeartbeat` | emitted **first and unconditionally**, before any query | missing ⇒ the observer stopped |
| `BackendHealthy` | `https://<host>/health` says `UP`, through the edge | `< 1` |
| `DatabaseReachable` | a `select 1` answered | `< 1` |
| `ResponsibilityMinutesSinceLastSettled` | age of the newest `SETTLED` window | `> 180` (2h windows + slack) |
| `ResponsibilityRunsFailed` | `responsibility_run` rows in `FAILED` | `> 0` |
| `ReviewExportMinutesSinceLastIngested` | age of the newest `SUCCEEDED` import attempt | `> 240` |
| `SourceFailuresNAVER` / `SourceFailuresCAFE24` | source rows of the **newest** run carrying a `failure_reason` | `> 0` |
| `MarketplaceWriteDeltaInquiry` / `...Review` | rows added since T0 | **`> 0` = CRITICAL** |

**The write delta is the run's stop line.** A shadow run is defined by it being zero; no number of green
heartbeats makes a non-zero acceptable. With no `SHADOW_WATCH_T0` the script emits **`-1`**, not a guessed
`0` — it cannot tell new from pre-existing, and guessing zero is the one lie it must not tell.

**A dead observer is indistinguishable from a failing system, on purpose.** Every alarm below uses
`--treat-missing-data breaching`, so a watcher that stops emitting alarms exactly like one reporting a
failure. That is why the heartbeat is emitted before the database is even touched.

### Read-only, and enforced

Every query is a `SELECT`, and the session is opened with `default_transaction_read_only = on` so a future
edit cannot write. `deploy/pilot/shadow-watch-guard.test.sh` (16 assertions) pins that, the absence of any
marketplace or model host, the absence of any recovery action, `Type=oneshot` + `Restart=no` + no
`OnFailure=`, the heartbeat ordering, and the `-1`-without-T0 rule.

### Install (host)

```bash
sudo SHADOW_WATCH_T0='2026-10-01T09:00:00+09:00' deploy/pilot/install-shadow-watch.sh
systemctl list-timers reviewnary-shadow-watch.timer
SHADOW_WATCH_DRY_RUN=1 deploy/pilot/shadow-watch.sh     # print without emitting
```

The installer refuses without `SHADOW_WATCH_T0` and without the AWS CLI, and writes no unit when it refuses.

### Alarms + SNS (AWS resources — a separate step)

```bash
TOPIC=$(aws sns create-topic --name reviewnary-shadow --region ap-northeast-2 --query TopicArn --output text)
aws sns subscribe --topic-arn "$TOPIC" --protocol email --notification-endpoint <ops-email> --region ap-northeast-2

alarm() {  # alarm <name> <metric> <op> <threshold> <periods>
  aws cloudwatch put-metric-alarm --region ap-northeast-2 \
    --alarm-name "shadow-$1" --namespace Reviewnary/Shadow --metric-name "$2" \
    --statistic Maximum --period 300 --evaluation-periods "$5" \
    --comparison-operator "$3" --threshold "$4" \
    --treat-missing-data breaching --alarm-actions "$TOPIC" --ok-actions "$TOPIC"
}
alarm heartbeat      ShadowWatchHeartbeat                    LessThanThreshold     1   2
alarm backend        BackendHealthy                          LessThanThreshold     1   2
alarm database       DatabaseReachable                       LessThanThreshold     1   2
alarm resp-stalled   ResponsibilityMinutesSinceLastSettled   GreaterThanThreshold  180 1
alarm resp-failed    ResponsibilityRunsFailed                GreaterThanThreshold  0   1
alarm export-stalled ReviewExportMinutesSinceLastIngested    GreaterThanThreshold  240 1
alarm src-naver      SourceFailuresNAVER                     GreaterThanThreshold  0   1
alarm src-cafe24     SourceFailuresCAFE24                     GreaterThanThreshold  0   1
# CRITICAL — one datapoint is enough, and -1 (no baseline) breaches too
alarm write-inquiry  MarketplaceWriteDeltaInquiry            GreaterThanThreshold  0   1
alarm write-review   MarketplaceWriteDeltaReview             GreaterThanThreshold  0   1
```

`Maximum` rather than `Average`: one bad datapoint in a period is the news, and averaging hides it.

### IAM — minimum, and split in two

**The instance role** needs exactly one action, scoped by namespace (`PutMetricData` has no resource ARN,
so the namespace condition key is the scope):

```json
{ "Version": "2012-10-17", "Statement": [ {
    "Sid": "EmitShadowMetricsOnly", "Effect": "Allow",
    "Action": "cloudwatch:PutMetricData", "Resource": "*",
    "Condition": { "StringEquals": { "cloudwatch:namespace": "Reviewnary/Shadow" } } } ] }
```

Nothing else. **Not** `PutMetricAlarm`, **not** any `sns:*`, **not** `DeleteAlarms` — a host that can create
or silence its own alarms is a host that can hide its own failure.

**The operator**, one time, from their own credentials (not the instance): `cloudwatch:PutMetricAlarm` ·
`cloudwatch:DescribeAlarms` · `sns:CreateTopic` · `sns:Subscribe` · `sns:GetTopicAttributes`.

The instance's other two grants stay as they are and are unrelated: `AmazonSSMManagedInstanceCore` for
admin access, and the S3 backup keys, which belong to a separate `put-object`-only principal.

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

## 6-A. Coupang joining later — account only, and the collect poller stays off

The first run has **no Coupang seller account**, so Coupang is not a source at all (§0). This is the path
for adding it afterwards: **connect the account, and the next Responsibility window reads its inquiries.**
No redeploy beyond an env file, no Self-Pilot, no collect scheduler.

### What had to change, and why nothing smaller would do

The audit found the wiring sufficient for everything except one thing, and that one thing was a real
coupling rather than a missing name. `CoupangConnectorConfiguration.effectiveReadGrant(selfPilotEnabled,
grant)` returns `""` unless the Self-Pilot Runtime is on, and `deploy.sh` refuses Self-Pilot on with the
collect poller off. So the pre-existing grant could not admit a Coupang read on a host whose only reader is
the Responsibility runtime — and turning both on would start routine collection, which is a different
posture than the one being measured.

The grant was also **process-scoped**. Its own docblock defends that as safe because there is one operator
and one local backend; on a shared cloud host the defence fails, since a second organisation that connects
Coupang and presses 「지금 동기화」 rides the same key.

So the seam is the smallest thing that fixes both: **the gate is now asked «for whom».**

* `CoupangInquiryReadGrant` — `inquiry-read-grant-id` + `inquiry-read-grant-org-ids`, resolved per call
  against the organisation the call is for. Not a Spring bean: it is constructed inside the one `@Bean`
  method that needs it, so nothing else in the application can inject a Coupang read grant.
* `CoupangInquiriesClient` takes a **resolver** instead of a constant string, and its two public reads take
  the organisation, which the connector already had in hand (`request.orgId()`). A call that loses the
  organisation resolves to no grant and is refused — forgetting it fails closed.
* Precedence is **narrow first**, in one named place (`inquiryReadGrantOf`): the org-scoped grant answers
  for the organisations this deployment named; where it says nothing the Self-Pilot grant answers exactly as
  before, so a deployment that sets neither new property is byte-identical.
* **ORDER_SUMMARY is deliberately not widened.** The orders client keeps the process-wide grant: it is not a
  responsibility source and nothing here asks for it. A test pins the orders construction statement.
* **WRITE is untouched and structurally closed.** `ensureLiveWriteAllowed(baseUrl, liveApprovalId)` has no
  grant parameter, so no value this grant produces can open a write. A test asserts the reply client cannot
  even name the grant.

### Order of operations

1. **Before** the account is connected: `SELLEROPS_CONNECTOR_COUPANG_ENABLED=true`. Safe with no account —
   no account ⇒ no source ⇒ nothing is called. The other order hurts: `collectable()` answers true when a
   channel has **no** collector at all, so an account connected while the connector is off keeps the source
   and records `CONNECTOR_UNAVAILABLE` in every window.
2. `SELLEROPS_CONNECTOR_COUPANG_INQUIRY_READ_GRANT_ID=spr-<hex>` — shape `^spr-[0-9a-f]{8,32}$`; a malformed
   id **refuses the boot** rather than disarming quietly.
3. `SELLEROPS_CONNECTOR_COUPANG_INQUIRY_READ_GRANT_ORG_IDS=<cloud org uuid>`. `*` is refused (parsed as an
   empty list, which admits nobody). Both blank ⇒ nothing admitted.
4. **Unchanged and staying that way**: `SELLEROPS_SELF_PILOT_ENABLED=false` ·
   `SELLEROPS_COLLECT_SCHEDULER_ENABLED=false` · `SELLEROPS_CONNECTOR_COUPANG_LIVE_APPROVAL_ID` empty ·
   `SELLEROPS_INQUIRY_PUBLISH_EXECUTION_ENABLED=false`.
5. `deploy/pilot/deploy.sh` (env-only; images do not rebuild), then the **seller enters the Coupang
   credentials in the product** — they never live in `pilot.env`.
6. The **next Responsibility window** resolves Coupang INQUIRY as a source and reads it.

### Register this host's EIP in Coupang's calling-IP list BEFORE step 5

Coupang admits signed calls only from registered IPs. Get the value from `deploy/pilot/egress-check.sh`,
which proves host and backend-container egress are the same address, and register **that** EIP in the
Coupang app's calling-IP list. Do it before the first Coupang read, or the first window reports
`AUTH_REQUIRED` / `EXECUTION_FAILED` (`GW.IP_NOT_ALLOWED`) instead of collecting.

> **Reported, not fixed.** `sellerops.connector.coupang.advertised-egress-ips` (read by
> `CoupangAdvertisedEgress`, shown on the Coupang connect screen so a seller knows which IP to register) has
> **no environment binding** in `application.yml`, while NAVER's equivalent does. On this host that screen
> shows an empty list. It is display-only — it gates nothing — so the value lives in this runbook instead.

## 7. Not in this run

Scheduled unattended execution as a *product* posture is still **Q-1 OPEN**
(`docs/review_acquisition_aside_v2.md` §12): this run is a bounded, approved experiment for one named
organisation and one named device, not a decision that it may ship. The Aside device lane is not used
here, and its missing compose/pilot env plumbing (`SELLEROPS_RESPONSIBILITY_ASIDE_*`) is left unfixed on
purpose — this path does not read it.
