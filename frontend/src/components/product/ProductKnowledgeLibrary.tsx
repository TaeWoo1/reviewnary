import { useEffect, useState } from "react";
import { Btn } from "../ui/Btn";
import { Empty } from "../ui/Empty";
import { SectionHeader } from "../ui/SectionHeader";
import { RecordRow, RecordRows, RowMain, RowMeta, RowPreview, RowTag, RowTitle } from "./record/RecordRow";
import { api } from "../../lib/apiClient";
import type {
  KnowledgeSourceType,
  KnowledgeSourceView,
  ProductVariantView,
} from "../../lib/types";
import { count, kstDate } from "../../lib/format";

/**
 * 상품 지식 — what the SELLER wrote about this product.
 *
 * <b>A different axis from the catalogue above it.</b> Listings and specs are what a channel stated;
 * this is what a person wrote, and the Agent is required to keep the two apart when it cites either.
 * The screen keeps them apart too: separate section, separate wording, separate provenance line.
 *
 * <b>`chunks` is shown because it is what retrieval can reach.</b> A document saved with zero
 * passages is a document the Agent will never quote, and a seller should learn that here rather than
 * from an answer that quietly did not use it.
 */
const TYPES: Array<{ value: KnowledgeSourceType; label: string; hint: string }> = [
  { value: "DESCRIPTION", label: "상품 설명", hint: "이 상품이 무엇인지, 어떤 점이 다른지" },
  { value: "FAQ", label: "자주 묻는 질문", hint: "고객이 반복해서 묻는 것과 그 답" },
  { value: "USAGE", label: "사용법", hint: "사용·설치·보관 방법" },
  { value: "POLICY", label: "정책", hint: "교환·반품·배송·A/S 기준" },
  { value: "LINK", label: "참고 자료", hint: "상세페이지 주소와 옮겨 적은 내용" },
];

const TYPE_LABEL: Record<KnowledgeSourceType, string> = {
  DESCRIPTION: "상품 설명",
  FAQ: "자주 묻는 질문",
  USAGE: "사용법",
  POLICY: "정책",
  LINK: "참고 자료",
};

/**
 * 상품 지식 섹션 — 제목·행·편집기까지 한 덩어리.
 *
 * <b>목록은 읽는 곳이 아니라 고르는 곳이다</b> (상품 상세 canonical, 2026-10-05). 전에는 세 건이 본문을
 * 통째로 펼쳐 화면의 3분의 1을 썼다. 여기 적힌 것을 끝까지 읽는 자리는 편집기이고, 목록은 어떤 지식이
 * 있는지와 그것을 AI가 인용할 수 있는지만 말한다.
 *
 * <b>읽기는 화면이 한다.</b> 수를 머리말과 상단 띠에 적으려면 화면이 그 수를 알아야 하는데, 라이브러리가
 * 제 읽기를 쥐고 있으면 같은 목록을 두 번 읽지 않고는 셀 수 없었다.
 */
export function ProductKnowledgeLibrary({
  productId,
  sources,
  failed,
  onChanged,
}: {
  productId: string;
  /** null은 아직 읽는 중이라는 뜻이다 — 빈 목록이 아니다. */
  sources: KnowledgeSourceView[] | null;
  failed: boolean;
  onChanged: () => Promise<void>;
}) {
  const [editing, setEditing] = useState<KnowledgeSourceView | "new" | null>(null);

  const reload = async () => {
    await onChanged();
    setEditing(null);
  };

  return (
    <div>
      <SectionHeader
        title={
          <>
            상품 지식{" "}
            {sources ? (
              <span className="font-semibold tabular-nums text-muted">{count(sources.length)}</span>
            ) : null}
          </>
        }
        action={
          editing === null && sources && sources.length > 0 ? (
            <button
              type="button"
              onClick={() => setEditing("new")}
              className="rounded text-sm font-semibold text-brand-700 underline-offset-4 hover:text-brand-800 hover:underline focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-700"
            >
              지식 추가
            </button>
          ) : null
        }
      />

      {failed ? (
        <p className="mt-3 text-warn">상품 지식을 불러오지 못했습니다.</p>
      ) : sources === null ? (
        <p className="mt-3 text-muted">불러오는 중…</p>
      ) : sources.length === 0 && editing === null ? (
        <div className="mt-3">
        <Empty
          title="아직 등록된 상품 지식이 없습니다"
          body="상품 설명·자주 묻는 질문·사용법·정책을 적어 두면, AI가 답변을 만들 때 이 내용을 근거로 사용합니다. 여기에 없는 내용은 지어내지 않습니다."
          action={<Btn onClick={() => setEditing("new")}>지식 추가</Btn>}
        />
        </div>
      ) : (
        <RecordRows>
          {(sources ?? []).map((source) => (
            <RecordRow key={source.id} onClick={() => setEditing(source)}>
              <RowMain>
                <RowTag>{TYPE_LABEL[source.sourceType]}</RowTag>
                {echoesBody(source) ? null : <RowTitle>{source.title}</RowTitle>}
                <RowPreview>{flatten(source.body)}</RowPreview>
              </RowMain>
              <RowMeta>
                {source.variantId ? <span>{source.variantName ?? "특정 규격"} 전용</span> : null}
                {source.authorName ? <span>{source.authorName}</span> : null}
                <time>{kstDate(source.updatedAt)}</time>
                {/* 0은 사실이다 — 저장은 됐지만 AI가 끌어 쓸 수 없는 지식이 있다는 것을,
                    답변이 조용히 그것을 쓰지 않는 쪽이 아니라 여기서 알아야 한다. */}
                {source.chunks === 0 ? (
                  <span className="text-warn">AI가 인용할 수 없습니다</span>
                ) : null}
              </RowMeta>
            </RecordRow>
          ))}
        </RecordRows>
      )}

      {editing !== null ? (
        <div className="mt-4">
        <KnowledgeEditor
          productId={productId}
          source={editing === "new" ? null : editing}
          onDone={reload}
          onCancel={() => setEditing(null)}
        />
        </div>
      ) : null}
    </div>
  );
}

/**
 * 제목이 본문의 첫 문장을 그대로 베낀 경우 — 한 줄을 두 번 쓰지 않는다.
 *
 * 채널에서 들어온 상품 설명은 제목 자리에 본문 앞머리가 그대로 들어 있어, 둘을 나란히 두면 같은 문장이
 * 두 번 잘린 채로 선다.
 */
function echoesBody(source: KnowledgeSourceView): boolean {
  const head = source.title.trim().slice(0, 18);
  return head.length > 0 && source.body.trim().startsWith(head);
}

/** 한 줄로 편 본문. 자르는 일은 행이 제 너비를 보고 한다. */
function flatten(body: string): string {
  return body.replace(/\s+/g, " ").trim();
}

/**
 * The write form.
 *
 * <b>Saving is the only thing that indexes.</b> There is no separate "reindex" action and no queue:
 * the backend rebuilds this document's passages inside the same transaction, so a seller never has to
 * wonder whether what they just wrote is usable yet.
 */
function KnowledgeEditor({
  productId,
  source,
  onDone,
  onCancel,
}: {
  productId: string;
  source: KnowledgeSourceView | null;
  onDone: () => Promise<void>;
  onCancel: () => void;
}) {
  const [sourceType, setSourceType] = useState<KnowledgeSourceType>(source?.sourceType ?? "USAGE");
  const [title, setTitle] = useState(source?.title ?? "");
  const [body, setBody] = useState(source?.body ?? "");
  const [sourceUrl, setSourceUrl] = useState(source?.sourceUrl ?? "");
  const [variantId, setVariantId] = useState(source?.variantId ?? "");
  const [variants, setVariants] = useState<ProductVariantView[]>([]);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let active = true;
    void api
      .getProductKnowledgeStrict(productId)
      .then((view) => active && setVariants(view.variants.filter((v) => v.optionName)))
      .catch(() => active && setVariants([]));
    return () => {
      active = false;
    };
  }, [productId]);

  const submit = async () => {
    setSaving(true);
    setError(null);
    try {
      const request = {
        sourceType,
        title: title.trim(),
        body: body.trim(),
        sourceUrl: sourceUrl.trim() || null,
        variantId: variantId || null,
      };
      if (source) {
        await api.updateProductKnowledgeSource(source.id, request);
      } else {
        await api.createProductKnowledgeSource(productId, request);
      }
      await onDone();
    } catch {
      // Deliberately not the server's message: a failure here is one of two things a seller can act
      // on, and a transport-shaped string is neither of them.
      setError("저장하지 못했습니다. 제목과 내용을 확인해 주세요. 같은 제목의 지식이 이미 있을 수 있습니다.");
    } finally {
      setSaving(false);
    }
  };

  const remove = async () => {
    if (!source) return;
    setSaving(true);
    try {
      await api.deleteProductKnowledgeSource(source.id);
      await onDone();
    } catch {
      setError("삭제하지 못했습니다.");
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="space-y-4 rounded-2xl border border-brand/30 bg-brand-50/30 p-5">
      <div className="grid gap-3 sm:grid-cols-2">
        <label className="block">
          <span className="text-sm font-medium text-ink">종류</span>
          <select
            value={sourceType}
            onChange={(e) => setSourceType(e.target.value as KnowledgeSourceType)}
            className="mt-1 w-full rounded-xl border border-line bg-surface px-3 py-2.5 text-base focus:border-brand focus:outline-none"
          >
            {TYPES.map((t) => (
              <option key={t.value} value={t.value}>
                {t.label} — {t.hint}
              </option>
            ))}
          </select>
        </label>
        <label className="block">
          <span className="text-sm font-medium text-ink">제목</span>
          <input
            value={title}
            onChange={(e) => setTitle(e.target.value)}
            maxLength={200}
            placeholder="예: 세탁 및 관리 방법"
            className="mt-1 w-full rounded-xl border border-line bg-surface px-3 py-2.5 text-base focus:border-brand focus:outline-none"
          />
        </label>
      </div>

      {/*
        적용 범위 — the second axis, and the only one a customer can be wrong about.

        Shown even when the product has no stored variants, because its absence is informative: a
        listing with one 규격 has one honest answer, and hiding the control would leave a seller
        wondering where per-규격 knowledge goes.
      */}
      <label className="block">
        <span className="text-sm font-medium text-ink">적용 범위</span>
        <select
          value={variantId}
          onChange={(e) => setVariantId(e.target.value)}
          className="mt-1 w-full rounded-xl border border-line bg-surface px-3 py-2.5 text-base focus:border-brand focus:outline-none"
        >
          <option value="">전체 상품 공통</option>
          {variants.map((variant) => (
            <option key={variant.id} value={variant.id}>
              {variant.optionName}
            </option>
          ))}
        </select>
        <span className="mt-1 block break-keep text-sm text-muted">
          규격에 따라 답이 달라지는 내용이면 규격을 골라 주세요. 다른 규격의 문의에는 사용하지 않습니다.
        </span>
      </label>

      <label className="block">
        <span className="text-sm font-medium text-ink">내용</span>
        <textarea
          value={body}
          onChange={(e) => setBody(e.target.value)}
          rows={8}
          placeholder={"고객에게 그대로 설명할 수 있는 문장으로 적어 주세요.\n\n빈 줄로 문단을 나누면 그 단위로 인용됩니다."}
          className="mt-1 w-full rounded-xl border border-line bg-surface px-3 py-2.5 text-base leading-relaxed focus:border-brand focus:outline-none"
        />
      </label>

      {sourceType === "LINK" ? (
        <label className="block">
          <span className="text-sm font-medium text-ink">원문 주소</span>
          <input
            value={sourceUrl}
            onChange={(e) => setSourceUrl(e.target.value)}
            placeholder="https://…"
            className="mt-1 w-full rounded-xl border border-line bg-surface px-3 py-2.5 text-base focus:border-brand focus:outline-none"
          />
          {/* Said plainly: SellerOps does not fetch it. Automatic acquisition is an approved action,
              never a side effect of saving a note. */}
          <span className="mt-1 block text-sm text-muted">
            주소는 출처로만 남습니다. reviewnary가 이 주소를 열어 내용을 가져오지는 않습니다.
          </span>
        </label>
      ) : null}

      {error ? <p className="text-warn">{error}</p> : null}

      <div className="flex flex-wrap items-center gap-2">
        <Btn onClick={submit} disabled={saving || !title.trim() || !body.trim()}>
          {saving ? "저장 중…" : "저장"}
        </Btn>
        <Btn variant="ghost" onClick={onCancel} disabled={saving}>
          취소
        </Btn>
        {source ? (
          <Btn variant="ghost" onClick={remove} disabled={saving} className="ml-auto text-bad">
            삭제
          </Btn>
        ) : null}
      </div>
    </div>
  );
}
