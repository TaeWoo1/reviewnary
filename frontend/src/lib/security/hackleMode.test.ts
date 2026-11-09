import { describe, expect, it } from "vitest";
import { HACKLE_MODE, hackleKeyOutsideItsMode } from "./hackleMode";

describe("hackleKeyOutsideItsMode", () => {
  it("refuses a Hackle key in every ordinary mode — dev, build, preview, test", () => {
    for (const mode of ["development", "production", "test"]) {
      expect(hackleKeyOutsideItsMode(mode, { VITE_HACKLE_SDK_KEY: "hk_x" })).toBe(true);
    }
  });

  it("allows it only in the explicit Hackle test mode", () => {
    expect(HACKLE_MODE).toBe("hackle");
    expect(hackleKeyOutsideItsMode("hackle", { VITE_HACKLE_SDK_KEY: "hk_x" })).toBe(false);
  });

  it("has nothing to refuse when the key is absent or blank", () => {
    expect(hackleKeyOutsideItsMode("production", {})).toBe(false);
    expect(hackleKeyOutsideItsMode("production", { VITE_HACKLE_SDK_KEY: "  " })).toBe(false);
  });
});
