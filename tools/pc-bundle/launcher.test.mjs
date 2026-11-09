// The launcher's first-run pieces, without a bundle: keys made once and kept, a damaged or missing keys file that stops
// instead of being made again, the settings a seller may not set, and the one-launcher lock.
//
//   node --test tools/pc-bundle/launcher.test.mjs
//
// Starting the real programs (database, backend, agent runtime) from a built bundle is verify-bundle.mjs's job.
import assert from "node:assert/strict";
import { mkdirSync, mkdtempSync, readFileSync, statSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { test } from "node:test";
import {
  KEY_NAMES, LauncherError, acquireLock, ensureKeys, layout, parseEnvFile, readSettings, releaseLock, settingAllowed,
} from "./launcher.mjs";

function fresh() {
  return layout(mkdtempSync(join(tmpdir(), "reviewnary-launcher-")));
}

test("keys are made once, with every key, readable only by the seller", () => {
  const L = fresh();
  const first = ensureKeys(L);
  assert.equal(first.made, true);
  for (const k of KEY_NAMES) assert.ok(first.keys[k], k);
  assert.equal(statSync(L.keys).mode & 0o777, 0o600);
  const text = readFileSync(L.keys, "utf8");

  const second = ensureKeys(L);
  assert.equal(second.made, false);
  assert.deepEqual(second.keys, first.keys);
  assert.equal(readFileSync(L.keys, "utf8"), text, "the file is not rewritten");
});

test("a keys file left half-written by a stop is never the keys file", () => {
  const L = fresh();
  mkdirSync(L.data, { recursive: true });
  // What writeAtomic leaves when the stop comes before its rename: a temp file beside, no keys.env.
  writeFileSync(`${L.keys}.tmp-12345`, "JWT_SECRET=half");
  const { keys, made } = ensureKeys(L);
  assert.equal(made, true);
  assert.notEqual(keys.JWT_SECRET, "half");
});

test("data without its keys stops the program instead of making new keys", () => {
  const L = fresh();
  mkdirSync(L.state, { recursive: true });
  writeFileSync(join(L.state, "pg-initialized"), "x");
  assert.throws(() => ensureKeys(L), (e) => e instanceof LauncherError && e.code === "KEYS_MISSING");
  assert.throws(() => readFileSync(L.keys), "nothing was written");
});

test("a keys file that lost a key stops the program and is left as it was", () => {
  const L = fresh();
  ensureKeys(L);
  const damaged = readFileSync(L.keys, "utf8").split("\n").filter((l) => !l.startsWith("VAULT_MASTER_KEY=")).join("\n");
  writeFileSync(L.keys, damaged);
  assert.throws(() => ensureKeys(L), (e) => e.code === "KEYS_DAMAGED" && e.message.includes("VAULT_MASTER_KEY"));
  assert.equal(readFileSync(L.keys, "utf8"), damaged);
});

test("the seller's settings: the AI capabilities yes, the launcher's own wiring no", () => {
  for (const ok of ["SELLEROPS_AGENT_REPLY_ENABLED", "SELLEROPS_AGENT_DRAFT_API_KEY", "SELLEROPS_DEV_FAKE_LLM",
    "SELLEROPS_INQUIRY_DECISION_VENDOR"]) assert.equal(settingAllowed(ok), true, ok);
  for (const no of ["SELLEROPS_AGENT_RUNTIME_URL", "SELLEROPS_AGENT_RUNTIME_REQUIRED", "SPRING_DATASOURCE_URL",
    "SELLEROPS_JWT_SECRET", "SELLEROPS_CORS_ORIGIN", "SERVER_ADDRESS", "SELLEROPS_LOCAL_SELLER_SCREEN_DIR",
    "SELLEROPS_AGENT_QUOTA_DAILY_LLM_CALLS_PER_ORG", "JAVA_TOOL_OPTIONS"]) assert.equal(settingAllowed(no), false, no);

  const L = fresh();
  mkdirSync(L.data, { recursive: true });
  writeFileSync(L.settings, "SELLEROPS_AGENT_REPLY_ENABLED=true\nSELLEROPS_AGENT_RUNTIME_REQUIRED=false\n");
  assert.throws(() => readSettings(L), (e) => e.code === "SETTING_REFUSED"
    && e.message.includes("SELLEROPS_AGENT_RUNTIME_REQUIRED") && !e.message.includes("false"));
});

test("one launcher per data folder; a lock left by a launcher that died is taken over", () => {
  const L = fresh();
  assert.deepEqual(acquireLock(L), { ok: true });
  // The holder is this test process — alive, and its command line names the launcher's test.
  const second = acquireLock(L);
  assert.equal(second.ok, false);
  assert.equal(second.holder.pid, process.pid);
  releaseLock(L);

  writeFileSync(L.lock, JSON.stringify({ pid: 999_999, nonce: "gone" }));
  assert.deepEqual(acquireLock(L), { ok: true });
  assert.equal(JSON.parse(readFileSync(L.lock, "utf8")).pid, process.pid);
  releaseLock(L);
});

test("env files: comments and blanks skipped, the first = splits", () => {
  assert.deepEqual(parseEnvFile("# x\n\nA=1\nB = two=2\n"), { A: "1", B: "two=2" });
});
