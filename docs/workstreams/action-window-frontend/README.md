# Action Window Frontend — Workstream

> Workstream: **UI/UX + Frontend**. Builds the user-facing SellerOps operations-agent
> experience. Consumes — never redefines — the shared Action Window contract.

## Mission

Build the surfaces where an SME seller watches the operations agent work, is asked to act
only at genuine human checkpoints, and sees the operation resume and complete.

## Ownership (this workstream builds)

- Review Operations UI · Human Checkpoint cards · Action Window control panel ·
  Operation Run timeline · completed result · UI fixtures and mock scenarios ·
  FE-owned copy-key registry · responsive design · accessibility · product copy.

## Explicit exclusions (never built here)

- Chrome · CDP · DOM traversal · target detection · overlay positioning · download
  detection · marketplace attribution · `collector/**` / `backend/**` · real Bridge runtime.

## Collaboration boundary

- FE renders the sanitized **`ActionWindowRunView`**.
- Runtime determines state, completion, blockers, and allowed commands.
- FE surfaces the user's *intent* (`REQUEST_STEP_RECHECK`); it never verifies an
  observation or completes a step. Runtime is the sole completion authority.
- Runtime sends semantic identifiers; **FE owns all final copy** (copy-key registry).

## Source-of-truth order

1. shared Action Window contract (`contracts/action-window/v1/`) — protocol semantics;
2. `docs/frontend/sellerops_frontend_spec.md`;
3. `docs/slices/action-window-v1.md`;
4. this workstream plan.

Surface conflicts rather than silently resolving them.

## Privacy invariant

No selector, raw URL, CDP ID, secret, cookie, raw account ID, or local path in any FE
surface, fixture, or test. FE consumes only sanitized enums / codes / copy keys / primitive
params produced upstream.

## First user flow (FE-1)

1. **Review Operations page** (`/operations`) — the run the seller is doing;
2. **Human Checkpoint** — the one thing the agent needs the person to do;
3. **Control Panel** — the allowed commands for the current run;
4. **Timeline** — the run's semantic progress;
5. **Completed result** — what the agent finished after the checkpoint.

## Status

FE-1 is **implemented** (mock-driven, no Bridge/Runtime). See `progress.md` for the live
state, files, and validation.

## The documents here

- [`progress.md`](./progress.md) — live state, files, validation. **Status lives here**, not in
  this README.
- [`implementation-plan.md`](./implementation-plan.md) — the FE-1 build plan.
- [`live-verification-protocol.md`](./live-verification-protocol.md) — *how* to verify the
  Operations UI against a live paired agent.
- [`live-verification-readiness.md`](./live-verification-readiness.md) — *whether it can be run
  yet*, and which runtime-owned pieces were missing. Its 2026-07-13 bottom line: the synthetic
  paired agent now exists (`collector` → `npm run action-window-ui-harness`) and the wire
  contract is proven hermetically by
  `collector/test/crossstack/fe-transport-real-bridge.test.ts`; what still needs the manual
  approval-gated protocol is the browser-rendered UI itself.
