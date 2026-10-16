import { useState } from "react";
import { copyText } from "../../lib/clipboard";

/**
 * One IP address with a copy control. A failed copy says so and leaves the text selectable — it never claims a copy
 * that did not happen (see the clipboard util).
 */
export function CopyableIp({ ip }: { ip: string }) {
  const [state, setState] = useState<"idle" | "copied" | "failed">("idle");

  const onCopy = async () => {
    const result = await copyText(ip);
    setState(result.ok ? "copied" : "failed");
  };

  return (
    <li className="flex items-center justify-between gap-3">
      <code className="select-all text-sm font-medium text-ink">{ip}</code>
      <button type="button" className="btn-ghost text-xs" onClick={onCopy}>
        {state === "copied" ? "복사됨" : state === "failed" ? "복사 실패 · 직접 선택" : "복사"}
      </button>
    </li>
  );
}
