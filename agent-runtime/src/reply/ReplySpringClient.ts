/**
 * **The reply graph's only way to the backend** (Reply Agent v1, 2026-10-28, `docs/standing/reply_agent_v1.md`).
 *
 * Five routes and no others — the run's steps under `/api/agent/reply/run/`. The run token rides on each request as a
 * bearer and lives only in this object for the length of one run: it is never logged, never put in graph state, never
 * written to a checkpoint or a run store. The backend reads the run's review from the token, so nothing here names a
 * review, and every step answers with closed words and counts — the review text and the draft stay in the backend.
 */

export const REPLY_RUN_ROUTES = {
  classify: "/api/agent/reply/run/classify",
  draft: "/api/agent/reply/run/draft",
  critique: "/api/agent/reply/run/critique",
  save: "/api/agent/reply/run/save",
  finish: "/api/agent/reply/run/finish",
} as const;

/**
 * One step can wait this long — a reasoning model on a long review — before the run gives up on it. Five steps at most
 * (judge · draft · critique · revise · critique), so a run fits inside the backend's wait (`AgentRuntimeClient.TIMEOUT`).
 */
export const STEP_TIMEOUT_MS = 60_000;

export interface Judged {
  readonly category: string | null;
  readonly needsResponse: boolean;
  readonly handoff: boolean;
  /** Why it goes to a person — CATEGORY_S · UNSURE · QUESTION_NO_FACTS …; a closed word, never review text. */
  readonly reason?: string | null;
  /** The seller's rule says this automatic run drafts nothing for a review that needs nothing. */
  readonly skip?: boolean;
}
export interface Drafted {
  readonly written: boolean;
  readonly revisions: number;
  /** A handoff the drafter found — KNOWLEDGE_MISSING · POLICY_CONFLICT; a closed word. */
  readonly reason?: string | null;
}
export interface Critiqued {
  readonly passed: boolean;
  readonly failed: readonly string[];
  /** The day's AI ceiling stopped the critique: the draft is held for review (검토 대기), not failed. */
  readonly pending?: boolean;
}
export interface Saved {
  readonly saved: boolean;
  readonly version: number | null;
  readonly reason: string | null;
}

export class ReplyStepError extends Error {
  constructor(readonly status: number, readonly step: string) {
    super(`reply step failed (${step}, ${status})`);
  }
}

export interface ReplyBackend {
  classify(): Promise<Judged>;
  draft(revise: boolean): Promise<Drafted>;
  critique(): Promise<Critiqued>;
  save(): Promise<Saved>;
  finish(outcome: string, reason: string | null): Promise<void>;
}

export class ReplySpringClient implements ReplyBackend {
  readonly #token: string;

  constructor(
    private readonly baseUrl: string,
    token: string,
    private readonly fetchImpl: typeof fetch = fetch,
  ) {
    this.#token = token;
  }

  classify(): Promise<Judged> {
    return this.post<Judged>("classify", REPLY_RUN_ROUTES.classify);
  }

  draft(revise: boolean): Promise<Drafted> {
    return this.post<Drafted>("draft", REPLY_RUN_ROUTES.draft, { revise });
  }

  critique(): Promise<Critiqued> {
    return this.post<Critiqued>("critique", REPLY_RUN_ROUTES.critique);
  }

  save(): Promise<Saved> {
    return this.post<Saved>("save", REPLY_RUN_ROUTES.save);
  }

  async finish(outcome: string, reason: string | null): Promise<void> {
    await this.post<unknown>("finish", REPLY_RUN_ROUTES.finish, { outcome, reason }, true);
  }

  private async post<T>(step: string, path: string, body?: unknown, empty = false): Promise<T> {
    let res: Response;
    try {
      res = await this.fetchImpl(`${this.baseUrl}${path}`, {
        method: "POST",
        headers: { Authorization: `Bearer ${this.#token}`, "Content-Type": "application/json" },
        body: JSON.stringify(body ?? {}),
        signal: AbortSignal.timeout(STEP_TIMEOUT_MS),
      });
    } catch {
      // Unreachable, or past the step's time: status 0, the step named, nothing else.
      throw new ReplyStepError(0, step);
    }
    if (!res.ok) {
      // Never the body: it may name the review. The step and the status are enough to act on.
      throw new ReplyStepError(res.status, step);
    }
    if (empty) return undefined as T;
    const text = await res.text();
    return (text ? JSON.parse(text) : {}) as T;
  }
}
