import { useCallback, useEffect, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { PageHead } from "../../components/ui/PageHead";
import { SectionHeader } from "../../components/ui/SectionHeader";
import { Empty } from "../../components/ui/Empty";
import { BtnLink } from "../../components/ui/Btn";
import { AgentLaunch } from "../../components/ui/AgentLaunch";
import { ProductKnowledgeLibrary } from "../../components/product/ProductKnowledgeLibrary";
import { ProductOpportunities } from "../../components/opportunity/ProductOpportunities";
import {
  KnowledgeDocumentAdd,
  KnowledgeDocumentList,
} from "../../components/knowledge/KnowledgeDocuments";
import { Disclosure } from "../../components/ui/Disclosure";
import {
  RecordRow,
  RecordRows,
  RowMain,
  RowMeta,
  RowPreview,
  RowState,
  RowTag,
  RowTitle,
} from "../../components/product/record/RecordRow";
import { RelationCounts, type RelationCount } from "../../components/product/record/RelationCounts";
import { ProductRail } from "../../components/product/record/ProductRail";
import {
  useProductRecord,
  type ProductEvidenceQuote,
} from "../../components/product/record/useProductRecord";
import { useApiData } from "../../lib/useApiData";
import { api } from "../../lib/apiClient";
import { count, kstDate } from "../../lib/format";
import type {
  InquiryRowItem,
  KnowledgeCandidateView,
  KnowledgeDocumentView,
  KnowledgeSourceView,
  ProductKnowledgeView,
  ProductReviewItem,
  ReviewIssueView,
} from "../../lib/types";
import { priceLabel, sellingStatusLabel } from "../../lib/productVocabulary";
import { useAgentSurface } from "../../lib/agentPanel";

/**
 * 상품 상세 — record page (상품 상세 canonical, 2026-10-05).
 *
 * <b>이 화면은 dashboard가 아니라 하나의 레코드다.</b> 전에는 숫자 상자 셋이 화면에서 제일 큰 물건이고
 * 그 숫자가 가리키는 문장은 한 줄도 없었다. 가격·채널·브랜드·분류는 1,700px 아래 표 안에 있었고,
 * 상품 지식 세 건이 본문을 통째로 펼쳐 화면의 3분의 1을 썼다. 레코드를 열었는데 그 레코드가 무엇인지는
 * 스크롤해야 알 수 있는 화면이었다.
 *
 * <b>구조는 둘로 나뉜다</b> — 본문은 이 상품이 거느린 객체들의 목록(기다리는 문의 · 반복되는 문제 ·
 * 근거 문장 · 최근 리뷰 · 상품 지식 · 개선 기회)이고, 320px 레일은 상품 자체의 속성이다. 목록은 한 행에
 * 한 객체, 한 줄 미리보기까지. 레퍼런스는 Attio의 record page와 Linear의 issue detail이고, 레일을
 * 오른쪽에 둔 것은 제목이 읽는 열 맨 위에 서는 이 제품의 문법 때문이다.
 *
 * <b>레일은 단일값만 갖는다.</b> 리스팅이 둘 이상이거나 옵션이 하나라도 있으면 그 데이터는 1:N이므로
 * 본문 섹션으로 올라간다 — 320px 안에서 두 채널의 가격은 서로를 가린다.
 *
 * <b>읽기는 전부 이미 있던 것이다.</b> 새 endpoint·새 지표·새 workflow 없음. 모든 숫자는 서버가 세어
 * 준 것이고, 모든 문은 그 숫자를 센 술어와 같은 술어로 좁혀진 목록을 연다.
 */
export function ProductDetail() {
  const { productId = "" } = useParams();
  const { data, loading, error } = useApiData<ProductKnowledgeView>(
    () => api.getProductKnowledgeStrict(productId),
    [productId],
  );
  // What the panel says it is looking at — the catalogue name, never customer text.
  useAgentSurface(
    data
      ? {
          surface: "product",
          productId,
          label: `이 상품 · ${data.name ?? "이름을 확인하지 못한 상품"}`,
        }
      : null,
  );

  const issues = data?.signals.issues ?? EMPTY_ISSUES;
  const record = useProductRecord(productId, issues);
  const library = useProductLibrary(productId);

  if (loading) {
    return <p className="text-muted">불러오는 중…</p>;
  }
  if (error || !data) {
    return (
      <Empty
        title="상품을 불러오지 못했습니다"
        body="이 상품이 없거나 정보를 읽는 중 문제가 생겼습니다."
        action={<BtnLink to="/products">상품 목록으로</BtnLink>}
      />
    );
  }

  const volume = data.signals.volume;
  const { waiting, reviews, quotes } = record;
  // 1:N이면 레일을 떠나 본문으로 올라간다 — ProductRail이 같은 조건을 본다.
  const promotedListings = data.listings.length > 1;
  const promotedVariants = data.variants.length > 0;

  const relations: RelationCount[] = [
    { label: "리뷰", value: volume.reviews, to: `/reviews?productId=${productId}` },
    { label: "문의", value: volume.inquiries, to: `/inquiries?productId=${productId}` },
    {
      label: "답변 대기",
      value: volume.unansweredInquiries,
      to: `/inquiries?productId=${productId}&status=UNANSWERED`,
      emphasis: true,
    },
    { label: "반복 문제", value: issues.length },
    { label: "문제 근거", value: volume.issueEvidence },
    // 읽지 못한 수는 0이 아니다 — 읽힌 것만 센다.
    ...(library.sources ? [{ label: "상품 지식", value: library.sources.length }] : []),
    ...(library.documents ? [{ label: "자료", value: library.documents.length }] : []),
  ];

  return (
    <div className="space-y-4">
      <div>
        <Link
          to="/products"
          className="rounded text-xs text-muted hover:text-ink focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-700"
        >
          ← 상품
        </Link>
        <div className="mt-1.5">
          <PageHead
            title={data.name ?? "이름을 확인하지 못한 상품"}
            description={data.sku ? `상품코드 ${data.sku}` : undefined}
            action={
              <AgentLaunch
                context={{ productId, surface: "product" }}
                label="이 상품에 대해 물어보기"
              />
            }
          />
        </div>
      </div>

      <RelationCounts items={relations} />

      <div className="flex flex-col items-start gap-10 lg:flex-row">
        <div className="min-w-0 flex-1 space-y-7">
          {/*
            지금 사람을 기다리는 것 — 이 화면에서 유일하게 시간이 흐르고 있는 목록이라 맨 위에 선다.
            숫자는 서버가 센 미답변 수이고, 행은 그 숫자를 센 것과 같은 술어로 읽은 첫 세 줄이다.
          */}
          {waiting && waiting.length > 0 ? (
            <section aria-label="답변을 기다리는 문의">
              <SectionHeader
                title={<Titled title="답변을 기다리는 문의" n={volume.unansweredInquiries} />}
                action={
                  <SectionDoor to={`/inquiries?productId=${productId}`}>
                    문의 {count(volume.inquiries)}건 모두 보기
                  </SectionDoor>
                }
              />
              <RecordRows>
                {waiting.map((row) => (
                  <WaitingRow key={row.inquiryId} row={row} />
                ))}
              </RecordRows>
              {volume.unansweredInquiries > waiting.length ? (
                <MoreLine to={`/inquiries?productId=${productId}&status=UNANSWERED`}>
                  답변 대기 {count(volume.unansweredInquiries - waiting.length)}건 더 보기
                </MoreLine>
              ) : null}
            </section>
          ) : null}

          {/*
            반복되는 문제 — 추출기가 묶은 것이지 리뷰 수를 센 것이 아니다.

            <p>숫자가 바뀌는 문이라 범위를 말한다: 여기 숫자는 이 상품의 것이고, 행이 여는 문제 화면은
            같은 문제를 모든 상품에서 센다 (16건 ↔ 18건, 둘 다 맞다). 한 번의 클릭 사이에 놓인 두 개의
            옳은 숫자는, 좁은 쪽이 무엇으로 좁혀졌는지 말하지 않으면 하나의 틀린 숫자로 읽힌다.
          */}
          <section aria-label="반복되는 문제">
            <SectionHeader
              title={<Titled title="반복되는 문제" n={issues.length} />}
              action={
                <span className="text-xs text-muted">
                  이 상품에서 나온 근거 {count(volume.issueEvidence)}건
                </span>
              }
            />
            {issues.length === 0 ? (
              <p className="mt-3 text-muted">이 상품에서 반복 문제로 잡힌 것이 없습니다.</p>
            ) : (
              <>
                <RecordRows>
                  {issues.slice(0, ISSUES_SHOWN).map((issue) => (
                    <IssueRow key={issue.id} issue={issue} />
                  ))}
                </RecordRows>
                {issues.length > ISSUES_SHOWN ? (
                  <Disclosure
                    label={`문제 ${count(issues.length - ISSUES_SHOWN)}건 더 보기`}
                    summaryClassName="-ml-2"
                  >
                    <RecordRows>
                      {issues.slice(ISSUES_SHOWN).map((issue) => (
                        <IssueRow key={issue.id} issue={issue} />
                      ))}
                    </RecordRows>
                  </Disclosure>
                ) : null}
              </>
            )}
          </section>

          {/*
            근거가 된 문장 — 위의 제목들이 무엇을 세었는지, 고객의 말로.

            상위 문제 셋의 가장 최근 문장 하나씩. 각 줄은 그 문장을 쓴 리뷰로 간다 — 리뷰를 판단하고
            답하는 화면은 이 제품에 하나뿐이다.
          */}
          {quotes && quotes.length > 0 ? (
            <section aria-label="문제의 근거가 된 문장">
              <SectionHeader
                title="문제의 근거가 된 문장"
                action={
                  <span className="text-xs text-muted">
                    고객이 쓴 {count(volume.issueEvidence)}건 가운데 {KO_COUNT[quotes.length] ?? quotes.length}
                  </span>
                }
              />
              <ul className="divide-y divide-line/70">
                {quotes.map((quote) => (
                  <QuoteRow key={`${quote.issueId}:${quote.reviewId}:${quote.unitOrdinal}`} quote={quote} />
                ))}
              </ul>
            </section>
          ) : null}

          {/* 최근 리뷰 — 1,761이라는 수가 무엇으로 이루어져 있는지 세 줄로. */}
          {reviews && reviews.length > 0 ? (
            <section aria-label="최근 리뷰">
              <SectionHeader
                title="최근 리뷰"
                action={
                  <SectionDoor to={`/reviews?productId=${productId}`}>
                    리뷰 {count(volume.reviews)}건 모두 보기
                  </SectionDoor>
                }
              />
              <RecordRows>
                {reviews.map((review) => (
                  <ReviewRow key={review.id} review={review} />
                ))}
              </RecordRows>
            </section>
          ) : null}

          {/* 판매자가 읽는 곳이 아니라 쓰는 곳 — 이 화면에서 유일하게 내용이 늘어나는 섹션. */}
          <section aria-label="상품 지식">
            <ProductKnowledgeLibrary
              productId={productId}
              sources={library.sources}
              failed={library.sourcesFailed}
              onChanged={library.reloadSources}
            />
            <ProductKnowledgeGaps productId={productId} />
            {library.documents && library.documents.length > 0 ? (
              <div className="mt-3">
                <KnowledgeDocumentList documents={library.documents} onChanged={library.reloadDocuments} />
              </div>
            ) : null}
            <ProductDocumentsLine
              documents={library.documents}
              productId={productId}
              onImported={library.reloadDocuments}
            />
          </section>

          {/* Opportunity Engine v1: 위의 문제들이 무엇으로 이어지는가. */}
          <ProductOpportunities productId={productId} />

          {/*
            1:N인 것은 여기로 올라온다 — 레일이 값 하나를 말할 수 없을 때.
          */}
          {promotedListings ? (
            <section aria-label="채널 리스팅">
              <SectionHeader
                title={<Titled title="채널 리스팅" n={data.listings.length} />}
                action={<span className="text-xs text-muted">채널마다 다른 이름·가격·상태</span>}
              />
              <RecordRows>
                {data.listings.map((listing, i) => (
                  <RecordRow key={`${listing.channelCode}-${listing.channelProductId ?? i}`}>
                    <RowMain>
                      <RowTag>{listing.channelNameKo ?? listing.channelCode}</RowTag>
                      <RowPreview strong>{listing.listingName ?? "이름 없음"}</RowPreview>
                    </RowMain>
                    <RowMeta>
                      <span className="font-semibold text-ink">
                        {listing.price == null ? "—" : priceLabel(count(listing.price), listing.currency)}
                      </span>
                      <span>{sellingStatusLabel(listing.sellingStatus)}</span>
                      <time>{listing.observedAt?.slice(0, 10)}</time>
                    </RowMeta>
                  </RecordRow>
                ))}
              </RecordRows>
            </section>
          ) : null}

          {promotedVariants ? (
            <section aria-label="옵션">
              <SectionHeader title={<Titled title="옵션" n={data.variants.length} />} />
              <RecordRows>
                {data.variants.map((variant) => (
                  <RecordRow key={variant.id}>
                    <RowMain>
                      <RowTitle>{variant.optionName ?? "이름 없음"}</RowTitle>
                      {variant.sku ? <RowPreview>{variant.sku}</RowPreview> : null}
                    </RowMain>
                    <RowMeta>
                      <span>
                        {variant.price == null ? "—" : priceLabel(count(variant.price), data.listings[0]?.currency)}
                      </span>
                    </RowMeta>
                  </RecordRow>
                ))}
              </RecordRows>
            </section>
          ) : null}
        </div>

        <aside className="w-full shrink-0 lg:w-[320px]">
          <ProductRail data={data} />
        </aside>
      </div>
    </div>
  );
}

/** 열어 둔 채 보이는 문제 수. 나머지는 접힌 채로, 수까지 말하고 있다. */
const ISSUES_SHOWN = 5;

const EMPTY_ISSUES: ReviewIssueView[] = [];

/** 「셋」 — 세 줄짜리 표본을 숫자로 적으면 수량처럼 읽힌다. */
const KO_COUNT: Record<number, string> = { 1: "하나", 2: "둘", 3: "셋" };

/** 제목 옆의 수 — 제목만큼 크지 않고, 제목과 떨어져 있지도 않다. */
function Titled({ title, n }: { title: string; n: number }) {
  return (
    <>
      {title}{" "}
      <span className="font-semibold tabular-nums text-muted">{count(n)}</span>
    </>
  );
}

/** 섹션이 좁혀 보여 준 목록 전체로 가는 길 — 섹션마다 하나뿐이다. */
function SectionDoor({ to, children }: { to: string; children: React.ReactNode }) {
  return (
    <Link
      to={to}
      className="rounded text-sm font-semibold text-brand-700 underline-offset-4 hover:text-brand-800 hover:underline focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-700"
    >
      {children} ›
    </Link>
  );
}

function MoreLine({ to, children }: { to: string; children: React.ReactNode }) {
  return (
    <Link
      to={to}
      className="mt-2.5 inline-block rounded text-sm text-muted hover:text-ink focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-700"
    >
      {children}
    </Link>
  );
}

/** 답변을 기다리는 문의 한 줄. 제목이 없는 문의는 본문이 그 자리를 가진다. */
function WaitingRow({ row }: { row: InquiryRowItem }) {
  return (
    <RecordRow to={`/inquiries/${row.inquiryId}`}>
      <RowMain>
        <RowTag>{row.channelNameKo ?? row.channelCode ?? "채널 미상"}</RowTag>
        {row.title ? <RowTitle>{row.title}</RowTitle> : null}
        <RowPreview>{row.snippet}</RowPreview>
      </RowMain>
      <RowMeta>
        <time>{kstDate(row.receivedAt)}</time>
        <span aria-hidden="true" className="text-brand-700">›</span>
      </RowMeta>
    </RecordRow>
  );
}

/**
 * 반복되는 문제 한 줄 — 상태와 기간까지.
 *
 * 전에는 제목과 숫자뿐이었다. 같은 「16건」이라도 사람이 이미 손을 댄 문제인지, 작년에 끝난 일인지는
 * 행이 말하지 않으면 열어 봐야만 알 수 있었다.
 */
function IssueRow({ issue }: { issue: ReviewIssueView }) {
  return (
    <RecordRow to={`/memory/${issue.id}`}>
      <RowMain>
        <RowTitle>{issue.title}</RowTitle>
        <RowState acting={issue.lifecycleState === "ACTING"}>{issue.lifecycleLabelKo}</RowState>
      </RowMain>
      <RowMeta>
        <span className="font-semibold text-ink">이 상품에서 {count(issue.evidenceCount)}건</span>
        <time>
          {month(issue.firstEvidenceOn)}~{month(issue.lastEvidenceOn)}
        </time>
        <span aria-hidden="true" className="text-brand-700">›</span>
      </RowMeta>
    </RecordRow>
  );
}

/** 2025-07-29 → 2025.07. 날짜까지 적으면 행의 오른쪽이 날짜 두 개로 가득 찬다. */
function month(day: string | null): string {
  return day ? day.slice(0, 7).replace("-", ".") : "—";
}

/** 고객이 쓴 문장 한 줄과, 그 문장을 쓴 리뷰로 가는 문. */
function QuoteRow({ quote }: { quote: ProductEvidenceQuote }) {
  return (
    <li className="py-3">
      <p className="break-keep leading-relaxed text-ink">“{quote.quote}”</p>
      <div className="mt-1 flex flex-wrap items-center gap-x-2.5 text-xs tabular-nums text-muted">
        <time>{quote.occurredOn}</time>
        {quote.rating != null ? <span>{quote.rating}점</span> : null}
        <span className="rounded-md bg-canvas px-[7px] py-0.5">{quote.issueTitle}</span>
        <Link
          to={`/reviews/reply/${quote.reviewId}`}
          className="ml-auto shrink-0 rounded font-semibold text-brand-700 transition hover:text-brand-800 focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-700"
        >
          이 리뷰 처리하기 ›
        </Link>
      </div>
    </li>
  );
}

/** 최근 리뷰 한 줄. 별점은 이 제품의 표기 그대로 「N점」이다. */
function ReviewRow({ review }: { review: ProductReviewItem }) {
  return (
    <RecordRow>
      <RowMain>
        <span className="shrink-0 text-sm tabular-nums text-muted">
          {review.rating == null ? "평점 없음" : `${review.rating}점`}
        </span>
        <RowPreview strong>{review.preview ?? "본문이 없는 리뷰"}</RowPreview>
      </RowMain>
      <RowMeta>
        <span>{review.channelNameKo ?? review.channelCode}</span>
        <time>{review.writtenOn}</time>
      </RowMeta>
    </RecordRow>
  );
}

/**
 * 이 상품의 지식과 자료를 읽어 두는 곳 — 수를 띠에 적으려면 화면이 그 수를 알아야 한다.
 *
 * 라이브러리가 제 읽기를 갖고 있던 때에는 같은 목록을 두 번 읽지 않고는 「상품 지식 3」을 적을 수 없었다.
 * 읽기는 여기 한 번, 쓰고 나서 다시 읽는 것도 여기 한 번이다.
 */
function useProductLibrary(productId: string) {
  const [sources, setSources] = useState<KnowledgeSourceView[] | null>(null);
  const [sourcesFailed, setSourcesFailed] = useState(false);
  const [documents, setDocuments] = useState<KnowledgeDocumentView[] | null>(null);

  const reloadSources = useCallback(async () => {
    try {
      setSources(await api.listProductKnowledgeSources(productId));
      setSourcesFailed(false);
    } catch {
      setSourcesFailed(true);
    }
  }, [productId]);

  const reloadDocuments = useCallback(async () => {
    setDocuments(await api.getKnowledgeDocuments(productId).catch(() => []));
  }, [productId]);

  useEffect(() => {
    setSources(null);
    setSourcesFailed(false);
    void reloadSources();
  }, [reloadSources]);

  useEffect(() => {
    setDocuments(null);
    void reloadDocuments();
  }, [reloadDocuments]);

  return { sources, sourcesFailed, documents, reloadSources, reloadDocuments };
}

/**
 * What is still waiting to be confirmed FOR THIS PRODUCT — 확인 필요, narrowed by the binding.
 *
 * <b>It is a pointer, not a second inbox.</b> The editor, the accept/dismiss decisions and the ask's
 * own wording all live on 알고 있는 정보, and duplicating them here would create a second place to
 * answer the same question. This says how many there are and opens the one place that answers them.
 *
 * <p>The read is the company's open list — the only one that exists — filtered by {@code productId},
 * which is the binding and not the display name. A failed read renders nothing rather than 「0건」:
 * this component cannot tell an empty list from an unread one, and must not claim it can.
 */
function ProductKnowledgeGaps({ productId }: { productId: string }) {
  const [open, setOpen] = useState<KnowledgeCandidateView[] | null>(null);

  useEffect(() => {
    let live = true;
    api
      .getKnowledgeCandidates()
      .then((rows) => {
        if (live) setOpen(rows.filter((row) => row.productId === productId));
      })
      .catch(() => {
        if (live) setOpen(null);
      });
    return () => {
      live = false;
    };
  }, [productId]);

  if (open === null || open.length === 0) return null;
  return (
    <p className="mt-3 flex flex-wrap items-center gap-x-2 gap-y-1 text-sm">
      <span className="break-keep text-ink">
        이 상품에 대해 확인이 필요한 항목이 {count(open.length)}건 있습니다.
      </span>
      <Link
        to="/knowledge"
        className="rounded font-semibold text-brand-700 underline-offset-4 hover:text-brand-800 hover:underline focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-700"
      >
        확인하러 가기
      </Link>
    </p>
  );
}

/**
 * 자료 — 이 상품에 대해 판매자가 이미 갖고 있던 파일.
 *
 * 올라온 파일이 있으면 그 목록이 위에 서고, 이 줄은 수와 올리는 길만 말한다. 섹션 하나를 따로 세우던
 * 자리였는데, 0건인 섹션 제목은 화면에서 제일 조용해야 할 것이 제일 큰 자리를 차지하는 일이었다.
 */
function ProductDocumentsLine({
  documents,
  productId,
  onImported,
}: {
  documents: KnowledgeDocumentView[] | null;
  productId: string;
  onImported: () => Promise<void>;
}) {
  if (documents === null) return null;
  return (
    <div className="mt-3 flex flex-wrap items-center gap-x-2 gap-y-1 text-sm text-muted">
      <span className="break-keep">
        자료 {count(documents.length)}건 — 사용설명서·FAQ 같은 파일을 올리면 답변 근거로 씁니다.
      </span>
      <KnowledgeDocumentAdd scope="PRODUCT" productId={productId} onImported={onImported} />
    </div>
  );
}
