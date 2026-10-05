import { useRef, useState } from "react";
import { isAxiosError } from "axios";
import { Btn } from "../ui/Btn";
import { api } from "../../lib/apiClient";
import { ORG_TOPICS, PRODUCT_TOPICS, scopeLabel, topicLabel } from "../../lib/knowledgeWords";
import type { KnowledgeDocumentView } from "../../lib/types";
import { kstDate } from "../../lib/format";

/**
 * <b>자료 — the material the company already had.</b> (Knowledge Setup &amp; Inbox UX v1 §5, §6)
 *
 * <p>Internally these are knowledge sources with a filename; to the seller they are simply 자료, and
 * what a row says about itself is what a person can answer about a file: what it is, what kind, where
 * it applies, whether it is current, when it arrived, and who brought it. The list used to state two
 * of those six, and the two it omitted — kind and provenance — are the ones that tell a shop owner
 * whether the manual they uploaded in March is the manual reviewnary is quoting today.
 *
 * <p><b>No chunks.</b> {@code passages} appears only as its one honest consequence: a document that
 * produced none cannot be quoted, and saying so beats letting the seller assume it works.
 *
 * <p><b>한 줄에 한 건, 열은 그 여섯 가지.</b> 지식 화면의 다른 두 표와 같은 문법으로 선다 — 자료가 기준과
 * 지식의 출처로 그 표들에 이름이 적히므로, 같은 회사의 같은 물건이 두 가지 모양으로 그려지면 둘이 같은
 * 것인지 읽히지 않는다.
 */
export function KnowledgeDocumentList({
  documents,
  onChanged,
}: {
  documents: KnowledgeDocumentView[];
  onChanged: () => Promise<void> | void;
}) {
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  if (documents.length === 0) {
    return (
      <p className="break-keep px-5 py-6 text-sm text-muted">
        사용설명서·FAQ·배송/교환 정책처럼 이미 쓰고 계신 자료를 올리면 답변 근거로 씁니다.
      </p>
    );
  }

  return (
    <>
      {error ? <p className="break-keep px-5 pt-3 text-sm text-bad" role="alert">{error}</p> : null}
      {/* 위의 두 표와 같은 문법 — 한 줄에 한 건, 열은 그 자료에 대해 사람이 답할 수 있는 것들.
          aria-hidden: 각 칸이 제 이름을 달고 있어 한 줄이 하나의 객체로 읽힌다. */}
      <div
        aria-hidden="true"
        className="flex items-center gap-4 border-b border-line bg-canvas/40 px-5 py-2 text-xs text-muted"
      >
        <span className="min-w-0 flex-1">자료</span>
        <span className="w-[110px] shrink-0">종류</span>
        <span className="w-[230px] shrink-0">적용 범위</span>
        <span className="w-[150px] shrink-0">올린 사람</span>
        <span className="w-[96px] shrink-0 text-right">올린 날짜</span>
        <span className="w-[88px] shrink-0 whitespace-nowrap" />
      </div>
      <ul className="divide-y divide-line/70" data-testid="knowledge-documents">
        {documents.map((document) => (
          <li key={document.sourceId} className="flex items-center gap-4 px-5 py-1.5">
            <span className="min-w-0 flex-1 truncate text-sm font-medium text-ink">
              {document.fileName ?? document.title}
              {document.passages === 0 ? <span className="ml-2 text-xs text-warn">내용 없음</span> : null}
              {document.active ? null : <span className="ml-2 text-xs text-muted">사용 안 함</span>}
            </span>
            <span className="w-[110px] shrink-0 truncate text-xs text-muted">{topicLabel(document.kind) ?? ""}</span>
            <span className="w-[230px] shrink-0 truncate text-xs text-muted">
              {scopeLabel(document.scope, document.productName)}
            </span>
            <span className="w-[150px] shrink-0 truncate text-xs text-muted">{document.uploadedBy ?? ""}</span>
            <span className="w-[96px] shrink-0 text-right text-xs tabular-nums text-muted">
              {kstDate(document.uploadedAt)}
            </span>
            <Btn
              className="w-[88px] shrink-0 whitespace-nowrap"
              size="sm"
              variant="ghost"
              disabled={busy === document.sourceId}
              onClick={async () => {
                setBusy(document.sourceId);
                setError(null);
                try {
                  await api.setKnowledgeDocumentActive(document.sourceId, !document.active);
                  await onChanged();
                } catch {
                  setError("자료 상태를 바꾸지 못했습니다.");
                } finally {
                  setBusy(null);
                }
              }}
            >
              {/* The label is the ACTION: three rows all captioned 「사용 안 함」 read as three retired
                  documents rather than three buttons that would retire them. */}
              {document.active ? "사용 중지" : "다시 사용"}
            </Btn>
          </li>
        ))}
      </ul>
    </>
  );
}


/**
 * <b>자료 추가 — the seller hands over a file they already have.</b>
 *
 * <p>Two questions and no more. <b>Scope is not one of them</b>: it is fixed by where the control
 * lives — the knowledge screen files company material, the product screen files that product's — so
 * a seller who opened this from a product is never asked which product. What is asked is the kind,
 * because the file cannot say it and retrieval reads it.
 *
 * <p>The seller confirms the FILE, never its passages. Asking them to approve forty chunks would be
 * asking them to do the chunker's job, and they would say yes to all of it without reading.
 */
export function KnowledgeDocumentAdd({
  scope,
  productId,
  onImported,
  label = "자료 추가",
}: {
  scope: "PRODUCT" | "ORG";
  productId?: string | null;
  onImported: () => void | Promise<void>;
  /** The closed control's words — 「+ 자료」 where a tab already says what it adds to. */
  label?: string;
}) {
  const topics = scope === "PRODUCT" ? PRODUCT_TOPICS : ORG_TOPICS;
  const input = useRef<HTMLInputElement>(null);
  const [open, setOpen] = useState(false);
  const [topic, setTopic] = useState(scope === "PRODUCT" ? "USAGE" : "GENERAL_CS_FAQ");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function upload(file: File) {
    setBusy(true);
    setError(null);
    try {
      await api.importKnowledgeDocument({
        scope,
        productId: scope === "PRODUCT" ? productId : null,
        sourceType: scope === "PRODUCT" ? topic : null,
        orgType: scope === "ORG" ? topic : null,
        file,
      });
      setOpen(false);
      await onImported();
    } catch (e) {
      // The backend's own sentence: it says which of the four refusals happened, and the seller can
      // act on each one differently.
      const message = isAxiosError(e) ? (e.response?.data as { message?: string })?.message : null;
      setError(message ?? "자료를 가져오지 못했습니다.");
    } finally {
      setBusy(false);
      if (input.current) input.current.value = "";
    }
  }

  if (!open) {
    return (
      <Btn size="sm" variant="outline" onClick={() => setOpen(true)}>
        {label}
      </Btn>
    );
  }

  return (
    <div className="w-full rounded-xl border border-line bg-surface p-4" data-testid="knowledge-document-add">
      <label className="block text-sm font-medium text-ink" htmlFor="document-topic">
        어떤 자료인가요
      </label>
      <select
        id="document-topic"
        className="mt-1 w-full rounded-lg border border-line bg-canvas p-2 text-base text-ink"
        value={topic}
        onChange={(e) => setTopic(e.target.value)}
        disabled={busy}
      >
        {topics.map((t) => (
          <option key={t.value} value={t.value}>
            {t.label} — {t.hint}
          </option>
        ))}
      </select>
      <p className="mt-1.5 break-keep text-sm text-muted">
        {scope === "PRODUCT" ? "이 상품에만 적용됩니다." : "회사 전체에 적용됩니다."} PDF · DOCX · TXT ·
        MD · CSV를 읽습니다.
      </p>
      <input
        ref={input}
        type="file"
        accept=".pdf,.docx,.txt,.md,.csv"
        className="hidden"
        aria-label="자료 파일"
        onChange={(e) => {
          const file = e.target.files?.[0];
          if (file) void upload(file);
        }}
      />
      {error ? <p className="mt-2 break-keep text-sm text-bad" role="alert">{error}</p> : null}
      <div className="mt-4 flex flex-wrap gap-2">
        <Btn onClick={() => input.current?.click()} disabled={busy}>
          {busy ? "가져오는 중…" : "파일 고르기"}
        </Btn>
        <Btn variant="outline" onClick={() => setOpen(false)} disabled={busy}>
          취소
        </Btn>
      </div>
    </div>
  );
}
