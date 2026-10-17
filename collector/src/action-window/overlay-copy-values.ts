/**
 * **Copy rows on the guidance panel — the one place a walk WRITES the seller's clipboard.**
 *
 * The NAVER call-IP step (2026-10-17, scope lock v1.14 ⑧-4 (b)) shows the IP the seller registers in the API
 * centre's 「API호출 IP」 row, with a copy button beside it, on the window they are pasting into. Every other
 * guidance module is forbidden the word `clipboard` outright, because what they must never do is READ one — a
 * copied Application Secret sits on that clipboard a step later. This module is the audited exception, and it is
 * narrow on purpose:
 *
 *  - **write only.** The in-page script calls `navigator.clipboard.writeText` with a string fixed at build time
 *    and nothing else on the clipboard API; a guard test asserts the script carries no read call.
 *  - **IPv4 only.** {@link copyableIpv4s} admits dotted-quad IPv4 strings and nothing else, at most three (NAVER's
 *    own limit), so this cannot become a way to put arbitrary text on the seller's clipboard.
 *  - **a person presses it.** The listener honours `isTrusted` events only, like the panel's advance button.
 *  - **it reads nothing.** No field, no page text, no value; it appends its own nodes to the panel the overlay
 *    already drew and returns a boolean.
 *
 * A failed write is said plainly and the address is selected for a manual copy — never a fake "복사됨".
 */
import type { Frame, Page } from "playwright";

type PageOrFrame = Page | Frame;

/** NAVER accepts at most three call IPs per application, so a panel never offers more. */
export const MAX_COPY_ROWS = 3;

const IPV4 = /^(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(\.(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)){3}$/;

/** The values a panel may offer to copy: well-formed IPv4 strings only, de-duplicated, at most {@link MAX_COPY_ROWS}. */
export function copyableIpv4s(values: readonly unknown[]): string[] {
  const out: string[] = [];
  for (const v of values) {
    if (typeof v !== "string" || !IPV4.test(v) || out.includes(v)) continue;
    out.push(v);
    if (out.length === MAX_COPY_ROWS) break;
  }
  return out;
}

/** The panel's wording for the rows — supplied by the walk, so this module carries no seller copy of its own. */
export interface CopyRowsCopy {
  copyLabel: string;
  copiedLabel: string;
  failedLabel: string;
  /** Shown instead of any row when there is no address to offer. */
  emptyNote: string;
}

/** The audited in-page script. Exported for the guard test; callers use {@link mountPanelCopyRows}. */
export function buildPanelCopyRowsScript(values: readonly string[], copy: CopyRowsCopy): string {
  const rows = copyableIpv4s(values);
  return `(function () {
  /* aw-panel-copy-rows (write-only clipboard; returns a boolean) */
  var panel = document.getElementById('__aw_advance_panel__');
  if (!panel) { return false; }
  var prior = document.getElementById('__aw_panel_copy_rows__');
  if (prior) { prior.remove(); }
  var ROWS = ${JSON.stringify(rows)};
  var COPY = ${JSON.stringify(copy)};
  var box = document.createElement('div');
  box.id = '__aw_panel_copy_rows__';
  box.style.cssText = 'display:flex;flex-direction:column;gap:6px';
  if (ROWS.length === 0) {
    var note = document.createElement('div');
    note.textContent = COPY.emptyNote;
    note.style.cssText = 'font-size:13px;color:#e6eeff;line-height:1.45';
    box.appendChild(note);
  }
  ROWS.forEach(function (ip) {
    var row = document.createElement('div');
    row.setAttribute('data-aw-copy-row', '');
    row.style.cssText = 'display:flex;gap:10px;align-items:center';
    var text = document.createElement('span');
    text.textContent = ip;
    text.style.cssText = 'flex:1 1 auto;font:600 15px ui-monospace,SFMono-Regular,Menlo,monospace;background:rgba(255,255,255,0.12);border-radius:6px;padding:6px 10px;user-select:all;-webkit-user-select:all';
    var btn = document.createElement('button');
    btn.setAttribute('type', 'button');
    btn.setAttribute('data-aw-copy', '');
    btn.textContent = COPY.copyLabel;
    btn.style.cssText = 'flex:0 0 auto;background:#fff;color:#0b1f4d;border:0;border-radius:8px;padding:8px 14px;font:600 13px system-ui,-apple-system,sans-serif;cursor:pointer';
    btn.addEventListener('click', function (ev) {
      if (!ev || ev.isTrusted !== true) { return; }
      function selectForManualCopy() {
        var sel = window.getSelection ? window.getSelection() : null;
        if (!sel) { return; }
        var range = document.createRange();
        range.selectNodeContents(text);
        sel.removeAllRanges();
        sel.addRange(range);
      }
      function failed() { btn.textContent = COPY.failedLabel; selectForManualCopy(); }
      try {
        if (!navigator.clipboard || typeof navigator.clipboard.writeText !== 'function') { failed(); return; }
        navigator.clipboard.writeText(ip).then(function () { btn.textContent = COPY.copiedLabel; }, failed);
      } catch (e) { failed(); }
    });
    row.appendChild(text);
    row.appendChild(btn);
    box.appendChild(row);
  });
  var detail = document.getElementById('__aw_panel_detail__');
  if (detail && detail.parentNode === panel) { panel.insertBefore(box, detail); } else { panel.appendChild(box); }
  return true;
})()`;
}

/**
 * Add the copy rows to the panel the overlay already drew. Value-free result: whether the rows were placed.
 * The panel must take pointer events (it does when it carries an advance button), or the rows cannot be pressed.
 */
export async function mountPanelCopyRows(page: PageOrFrame, values: readonly string[], copy: CopyRowsCopy): Promise<boolean> {
  const placed = await page.evaluate(buildPanelCopyRowsScript(values, copy));
  return placed === true;
}
