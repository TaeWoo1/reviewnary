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
  const [notice, setNotice] = useState<string | null>(null);

  if (documents.length === 0) {
    return (
      <p className="break-keep py-4 text-sm text-muted">
        사용설명서, 자주 묻는 질문, 배송·교환 정책처럼 이미 쓰는 파일을 올리면 답변이 참고해요.
      </p>
    );
  }

  return (
    <>
      {error ? <p className="break-keep pt-2 text-sm text-bad" role="alert">{error}</p> : null}
      {notice ? (
        <p className="break-keep pt-2 text-sm text-ink" role="status" data-testid="knowledge-document-replaced">
          {notice}
        </p>
      ) : null}
      {/* 위의 두 표와 같은 문법 — 한 줄에 한 건, 열은 그 자료에 대해 사람이 답할 수 있는 것들.
          aria-hidden: 각 칸이 제 이름을 달고 있어 한 줄이 하나의 객체로 읽힌다. */}
      <div
        aria-hidden="true"
        className="hidden items-center gap-4 pb-1 text-xs text-muted sm:flex"
      >
        <span className="min-w-0 flex-1">자료</span>
        <span className="w-[110px] shrink-0">종류</span>
        <span className="w-[230px] shrink-0">적용 범위</span>
        <span className="w-[150px] shrink-0">올린 사람</span>
        <span className="w-[96px] shrink-0 text-right">올린 날짜</span>
        <span className="w-[184px] shrink-0 whitespace-nowrap" />
      </div>
      <ul data-testid="knowledge-documents">
        {documents.map((document) => (
          <li
            key={document.sourceId}
            className="flex flex-wrap items-center gap-x-4 gap-y-1 border-t border-line/70 py-2 sm:flex-nowrap"
          >
            {/* 휴대폰에서는 한 줄에 다 들어가지 않는다(열 너비 합이 화면보다 넓다): 이름과 적용일이 첫 줄, 나머지가
                그 아래 한 줄로 내려간다. 넓은 화면은 지금까지의 표 그대로다. */}
            <div className="min-w-0 basis-full sm:basis-0 sm:flex-1">
              <span className="block truncate text-sm font-medium text-ink">
                {document.fileName ?? document.title}
                {document.passages === 0 ? <span className="ml-2 text-xs text-warn">내용 없음</span> : null}
                {document.active ? null : <span className="ml-2 text-xs text-muted">사용 안 함</span>}
              </span>
              {(document.version ?? 1) > 1 && document.updatedAt ? (
                <span className="block text-xs text-muted" data-testid="knowledge-document-revision">
                  {document.version}판 · {kstDate(document.updatedAt)} 파일 바꿈
                </span>
              ) : null}
              <EffectiveFrom document={document} onChanged={onChanged} />
            </div>
            {/* 휴대폰: 종류 · 범위 · 날짜는 그 자료를 고르는 근거라 자르지 않고 한 줄을 다 쓰고, 버튼 둘은 다음 줄로 내려간다. */}
            <span className="min-w-0 basis-full break-keep text-xs text-muted sm:hidden">
              {[topicLabel(document.kind), scopeLabel(document.scope, document.productName), kstDate(document.uploadedAt)]
                .filter(Boolean)
                .join(" · ")}
            </span>
            <span className="hidden w-[110px] shrink-0 truncate text-xs text-muted sm:block">
              {topicLabel(document.kind) ?? ""}
            </span>
            <span className="hidden w-[230px] shrink-0 truncate text-xs text-muted sm:block">
              {scopeLabel(document.scope, document.productName)}
            </span>
            <span className="hidden w-[150px] shrink-0 truncate text-xs text-muted sm:block">{document.uploadedBy ?? ""}</span>
            <span className="hidden w-[96px] shrink-0 text-right text-xs tabular-nums text-muted sm:block">
              {kstDate(document.uploadedAt)}
            </span>
            <ReplaceFile
              document={document}
              disabled={busy !== null}
              onStart={() => {
                setBusy(document.sourceId);
                setError(null);
                setNotice(null);
              }}
              onFailed={(message) => {
                setBusy(null);
                setError(message);
              }}
              onReplaced={async (replaced) => {
                // The file IS replaced (the server said 200). Only the list read that follows can still fail, and that
                // failure must not read as «not replaced» — re-uploading would make a 3판 nobody meant.
                setNotice(
                  `「${document.fileName ?? document.title}」을(를) 「${replaced.fileName ?? replaced.title}」 파일로 바꿨어요 · ${replaced.version ?? 1}판${replaced.active ? "" : " · 사용 안 함 그대로"}`,
                );
                try {
                  await onChanged();
                } catch {
                  setError("파일은 바뀌었어요. 목록을 다시 불러오지 못했어요 — 화면을 새로고침해 주세요. 다시 올릴 필요는 없어요.");
                } finally {
                  setBusy(null);
                }
              }}
            />
            <Btn
              className="w-[88px] shrink-0 whitespace-nowrap max-sm:!min-h-11"
              size="sm"
              variant="ghost"
              disabled={busy !== null}
              onClick={async () => {
                setBusy(document.sourceId);
                setError(null);
                setNotice(null);
                try {
                  await api.setKnowledgeDocumentActive(document.sourceId, !document.active);
                  await onChanged();
                } catch {
                  setError("바꾸지 못했어요. 다시 눌러 주세요.");
                } finally {
                  setBusy(null);
                }
              }}
            >
              {/* The label is the ACTION: three rows all captioned 「사용 안 함」 read as three retired
                  documents rather than three buttons that would retire them. */}
              {document.active ? "쓰지 않기" : "다시 켜기"}
            </Btn>
          </li>
        ))}
      </ul>
    </>
  );
}


/**
 * <b>파일 바꾸기</b> (2026-11-16) — one button. The seller picks the new file; the document stays the same document (its
 * kind, where it applies, 사용 여부 and 적용일 are kept) and the screen then shows its new 판. A file that cannot be read
 * changes nothing, and the reason is the server's own sentence. No folder is watched: a changed file on the seller's
 * computer reaches the service only through this button.
 */
function ReplaceFile({
  document,
  disabled,
  onStart,
  onFailed,
  onReplaced,
}: {
  document: KnowledgeDocumentView;
  disabled: boolean;
  onStart: () => void;
  /** The server refused or never answered: nothing about the document changed. */
  onFailed: (message: string) => void;
  /** The server replaced the file (200). What happens after — refreshing the list — is the caller's. */
  onReplaced: (replaced: KnowledgeDocumentView) => Promise<void>;
}) {
  const input = useRef<HTMLInputElement>(null);
  return (
    <>
      <input
        ref={input}
        type="file"
        accept=".pdf,.docx,.txt,.md,.csv"
        className="hidden"
        aria-label={`${document.fileName ?? document.title} 새 파일`}
        onChange={async (e) => {
          const file = e.target.files?.[0];
          if (input.current) input.current.value = "";
          if (!file) return;
          onStart();
          let replaced: KnowledgeDocumentView;
          try {
            replaced = await api.replaceKnowledgeDocumentFile(document.sourceId, file);
          } catch (err) {
            const message = isAxiosError(err) ? (err.response?.data as { message?: string })?.message : null;
            onFailed(`${document.fileName ?? document.title}: ${message ?? "파일을 바꾸지 못했어요."} 기존 자료는 그대로예요.`);
            return;
          }
          await onReplaced(replaced);
        }}
      />
      <Btn
        className="w-[88px] shrink-0 whitespace-nowrap max-sm:ml-auto max-sm:!min-h-11"
        size="sm"
        variant="ghost"
        disabled={disabled}
        onClick={() => input.current?.click()}
      >
        파일 바꾸기
      </Btn>
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
      setError(message ?? "파일을 가져오지 못했어요.");
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
        {scope === "PRODUCT" ? "이 상품에만 써요." : "가게 전체에 써요."} PDF · DOCX · TXT ·
        MD · CSV를 읽어요.
      </p>
      <input
        ref={input}
        type="file"
        accept=".pdf,.docx,.txt,.md,.csv"
        className="hidden"
        aria-label="올릴 파일"
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

/**
 * <b>적용일</b> (2026-11-15, owner decision 1): when this document's rules apply from. Optional and read only when two
 * documents disagree — never the upload date. Saving it does not edit the document, so no answer citing it is held.
 */
function EffectiveFrom({
  document,
  onChanged,
}: {
  document: KnowledgeDocumentView;
  onChanged: () => void | Promise<void>;
}) {
  const [editing, setEditing] = useState(false);
  const [value, setValue] = useState(document.effectiveFrom ?? "");
  const [saving, setSaving] = useState(false);
  const [failed, setFailed] = useState(false);
  async function save(next: string | null) {
    setSaving(true);
    setFailed(false);
    try {
      await api.setKnowledgeEffectiveFrom(document.sourceId, next);
      setEditing(false);
      await onChanged();
    } catch {
      setFailed(true);
    } finally {
      setSaving(false);
    }
  }
  if (!editing) {
    return (
      <button
        type="button"
        className="rounded text-xs font-normal text-muted underline-offset-4 hover:underline focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-700"
        onClick={() => setEditing(true)}
      >
        {document.effectiveFrom ? `적용일 ${document.effectiveFrom}` : "적용일 적기"}
      </button>
    );
  }
  return (
    <span className="inline-flex flex-wrap items-center gap-1 align-middle">
      <input
        type="date"
        aria-label={`${document.fileName ?? document.title} 적용일`}
        className="rounded border border-line bg-surface px-1 py-0.5 text-xs text-ink"
        value={value}
        onChange={(e) => setValue(e.target.value)}
      />
      <Btn size="sm" variant="outline" disabled={saving || value === ""} onClick={() => void save(value)}>
        저장
      </Btn>
      {document.effectiveFrom ? (
        <Btn size="sm" variant="ghost" disabled={saving} onClick={() => void save(null)}>
          지우기
        </Btn>
      ) : null}
      <Btn size="sm" variant="ghost" disabled={saving} onClick={() => setEditing(false)}>
        닫기
      </Btn>
      {failed ? <span className="text-xs text-bad" role="alert">저장하지 못했어요</span> : null}
    </span>
  );
}
