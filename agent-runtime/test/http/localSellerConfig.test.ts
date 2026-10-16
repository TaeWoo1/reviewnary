/**
 * Seller-PC posture of the Agent Runtime — scope lock v1.14 ① · ⑤, proposal §18.7 L1.
 *
 *  - `AGENT_RUNTIME_HOST` binds the listener (unset = Node's default, which the compose stack relies on).
 *  - Vendor tracing is forced OFF at boot. Asserted through LangSmith's OWN `isTracingEnabled`, not by
 *    reading back the variables this module wrote: each switch is first shown to turn tracing ON, so the
 *    "off" half cannot pass because the check reads the wrong thing.
 */
import { afterEach, describe, expect, it } from "vitest";
import { isTracingEnabled } from "langsmith";
import { VENDOR_TRACING_ENV, disableVendorTracing, loadConfig } from "../../src/http/config";

describe("listen host", () => {
  it("is unset by default so existing deployments keep listening as before", () => {
    expect(loadConfig({}).host).toBeUndefined();
    expect(loadConfig({ AGENT_RUNTIME_HOST: "  " }).host).toBeUndefined();
  });

  it("is the configured interface when set", () => {
    expect(loadConfig({ AGENT_RUNTIME_HOST: "127.0.0.1" }).host).toBe("127.0.0.1");
  });
});

describe("vendor tracing", () => {
  const saved = new Map<string, string | undefined>(VENDOR_TRACING_ENV.map((n) => [n, process.env[n]]));

  afterEach(() => {
    for (const [name, value] of saved) {
      if (value === undefined) delete process.env[name];
      else process.env[name] = value;
    }
  });

  for (const name of VENDOR_TRACING_ENV) {
    it(`${name}=true turns LangSmith tracing on, and disableVendorTracing turns it off`, () => {
      for (const other of VENDOR_TRACING_ENV) delete process.env[other];
      process.env[name] = "true";
      expect(isTracingEnabled()).toBe(true);

      disableVendorTracing();
      expect(isTracingEnabled()).toBe(false);
    });
  }
});
