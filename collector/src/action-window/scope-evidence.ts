/**
 * The sanitized scope-evidence value — how a guided run's exported scope was established.
 *
 * A zero-import leaf so it can be shared by BOTH the network layer (`../upload`, which sends it to the backend)
 * AND the browser drivers (which only pass it through). The drivers must never import `../upload` — their
 * source guards forbid it — so the shared type lives here instead of in the upload client.
 *
 * `MACHINE_MATCHED` means the runtime read the selected range back and it matched; `OPERATOR_CONFIRMED` means
 * the seller attested to it because it could not be read. The engine is the single authority that decides which
 * one a run records (see `import-engine.ts` `onScopeRead`); everything else only carries the value.
 */
export type ScopeEvidenceWire = "MACHINE_MATCHED" | "OPERATOR_CONFIRMED";

/**
 * **The list's displayed total for the selected range — the number the backend reconciles the file against.**
 *
 * Read off the marketplace at the same moment as a MACHINE-matched scope read, and sent with the segment ingest
 * as the optional `expectedRows` field. The backend treats a difference from the file's accounted rows as a
 * FAILED attempt, so a wrong number costs the seller a segment: everything that is not a plain non-negative
 * safe integer is `null`, and `null` means the field is omitted entirely — never a guess, never a default.
 *
 * Lives in this zero-import leaf for the same reason as {@link ScopeEvidenceWire}: the engine, the drivers and
 * the network layer all carry it, and the drivers may not import `../upload`.
 */
export function expectedRowsOrNull(value: unknown): number | null {
  if (typeof value !== "number" || !Number.isSafeInteger(value) || value < 0) return null;
  return value;
}
