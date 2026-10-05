import { useEffect, useState } from "react";
import { PageHead } from "../../components/ui/PageHead";
import { ListBox } from "../../components/ui/Section";
import { ProductTableHead, ProductTableRowItem } from "../../components/product/list/ProductTable";
import { Empty } from "../../components/ui/Empty";
import { BtnLink } from "../../components/ui/Btn";
import { AgentLaunch } from "../../components/ui/AgentLaunch";
import { api } from "../../lib/apiClient";
import { count } from "../../lib/format";
import type { ProductSummaryView } from "../../lib/types";
import type { ProductRowFacts } from "../../lib/productRows";
import { useAgentSurface } from "../../lib/agentPanel";

/**
 * 상품 — an object list, not a SKU table (docs/reviewnary_design.md §7).
 *
 * <b>A product is "what reviewnary knows about this product's operations" — and this screen is the
 * index of the page that reads one.</b> The counts are one aligned column each, named exactly as 상품
 * 상세's relation strip names them, so the two screens stop calling one object two things (this page
 * said 「답변 기준」 where the record page says 「상품 지식」). `ProductTable` owns that contract and the
 * reasoning behind it. The whole row is still the control, so there is no [열기] beside it: a
 * link-coloured word repeated down the right edge of every row is a second copy of the action the row
 * already is. Facts are read per product from `/api/products/{id}/signals` and the knowledge source
 * list, both fail-soft: a row whose reads failed says so, never 0, and the list never waits for them.
 *
 * <b>The screen does not order the catalogue; it renders the order it was sent.</b> It used to
 * re-sort the rows by a rule of its own, and that rule's second key (문제 근거) was not the server's
 * (부정 리뷰 수) — so which twenty products reached the page and the order they stood in were decided
 * by different quantities, one of which appeared nowhere on screen. There is one ordering now and it
 * lives where the whole catalogue can be seen: `ProductCatalogService`, 답변 대기 → 문제 근거 → 리뷰 →
 * 이름, with ingest's `(미지정 상품)` bucket last. Re-deriving it here is what made them disagree, so
 * this file states the order and renders it rather than computing it.
 *
 * <b>But a screen can only order what it was sent.</b> With no query this used to read the product
 * RESOLVER, whose empty-query head is alphabetical and capped at ten — so on 2026-09-04 the demo org
 * showed ten of its 308 products, six of them with no inquiry and no review at all, while the product
 * carrying 1,761 reviews was not on the page. It reads `/api/products/catalog` now: the same rule this
 * screen already sorted by, applied to the whole catalogue by the only layer that can see it.
 *
 * <b>A page is not a total.</b> The header printed the length of the list it happened to receive as
 * 「상품 10개」. It says how many the org has, and the list's own footer says how much of it is on the
 * page — which used to be a loose paragraph below the box, where it read as advice rather than as the
 * list stating its own extent.
 *
 * Search is server-side and debounced, because the catalogue is not small — and search still asks the
 * resolver, which is the read that question belongs to.
 */
const PAGE_SIZE = 20;

export function Products() {
  useAgentSurface({ surface: "products", label: "상품 목록" });
  const [query, setQuery] = useState("");
  const [rows, setRows] = useState<ProductSummaryView[] | null>(null);
  const [total, setTotal] = useState<number | null>(null);
  const [facts, setFacts] = useState<Map<string, ProductRowFacts>>(new Map());
  const [error, setError] = useState(false);

  useEffect(() => {
    let active = true;
    setError(false);
    const timer = setTimeout(() => {
      const request = query.trim()
        ? api.searchProductsStrict(query, PAGE_SIZE).then((list) => ({ rows: list, total: null }))
        : api.getProductCatalogStrict(PAGE_SIZE).then((page) => ({ rows: page.rows, total: page.total }));
      void request
        .then(({ rows: list, total: count }) => {
          if (!active) return;
          setRows(list);
          setTotal(count);
          void loadFacts(list, (next) => active && setFacts(next));
        })
        .catch(() => active && setError(true));
    }, query ? 250 : 0);
    return () => {
      active = false;
      clearTimeout(timer);
    };
  }, [query]);

  // The catalogue read failed, or the org genuinely holds nothing: the emptiness IS the page, and a
  // search box over a catalogue that has no rows to find is noise. A search that found nothing is a
  // different state — it keeps the toolbar, because the words to change are in it.
  const noCatalogue = error;
  const noProducts = !error && rows !== null && rows.length === 0 && !query;


  return (
    <div className="space-y-6">
      <PageHead
        title="상품"
        meta={
          rows && rows.length > 0 ? (
            <span className="text-sm text-muted">
              {query ? `찾은 상품 ${rows.length}개` : total === null ? `${rows.length}개` : `전체 ${count(total)}개`}
            </span>
          ) : undefined
        }
        action={<AgentLaunch context={{ surface: "products" }} label="상품에 대해 물어보기" />}
      />

      {/* The list owns its own toolbar — the seller's words, and the order the rows are in — because
          both are facts ABOUT this list. The search used to float above the box and the order was
          explained in a paragraph below it, so the two things that qualify the twenty rows sat on
          either side of them. The statement is not a control: it names the order `ProductCatalogService`
          ranked the whole catalogue by, and offering to change it would be a workflow this product has
          not got. It is hidden while searching, where the rows come back best-match first and answer a
          different question. */}
      {noCatalogue ? (
        <Empty
          title="상품 목록을 불러오지 못했습니다"
          body="잠시 후 다시 시도해 주세요. 상품은 채널을 연결하면 자동으로 채워집니다."
          action={<BtnLink to="/connect">채널 연결 열기</BtnLink>}
        />
      ) : noProducts ? (
        <Empty
          title="아직 상품이 없습니다"
          body="채널을 연결하면 판매 중인 상품이 여기에 채워집니다."
          action={<BtnLink to="/connect">채널 연결하기</BtnLink>}
        />
      ) : (
        <ListBox ariaLabel="상품 목록">
          <div className="flex flex-wrap items-center gap-x-4 gap-y-2 border-b border-line px-5 py-3">
            <label className="min-w-0 flex-1 sm:max-w-[360px]">
              <span className="sr-only">상품 이름 또는 상품코드로 검색</span>
              <input
                type="search"
                value={query}
                onChange={(e) => setQuery(e.target.value)}
                placeholder="상품 이름이나 상품코드로 찾기"
                className="w-full rounded-lg border border-line bg-surface px-3 py-1.5 text-sm focus:border-brand-700 focus:outline-none"
              />
            </label>
            {query ? null : (
              <p className="ml-auto text-xs text-muted">
                <span aria-hidden="true">⇅ </span>
                <b className="font-semibold text-ink">답변 대기</b> → 문제 근거 → 리뷰 순
              </p>
            )}
          </div>

          {rows === null ? (
            <p className="px-5 py-6 text-sm text-muted">불러오는 중…</p>
          ) : rows.length === 0 ? (
            <div className="px-5 py-6">
              <Empty compact title="찾는 상품이 없습니다" body="다른 이름이나 상품코드로 찾아보세요." />
            </div>
          ) : (
            <>
              <ProductTableHead />
              <ul className="divide-y divide-line/70">
                {rows.map((row) => (
                  <li key={row.id}>
                    <ProductTableRowItem
                      row={{ id: row.id, name: productNameNode(row.name), sku: row.sku, facts: facts.get(row.id) }}
                    />
                  </li>
                ))}
              </ul>
              {/* The list states its own extent where the list is, rather than in a paragraph
                  underneath it. Said whenever the page is smaller than the catalogue — not when the
                  page happens to be exactly full, which is how the old form (`rows.length >=
                  PAGE_SIZE`, against a head of ten) never rendered at all. */}
              {total !== null && total > rows.length ? (
                <p className="border-t border-line bg-canvas/40 px-5 py-2.5 text-xs text-muted">
                  <b className="font-semibold text-ink">{count(total)}개</b> 가운데{" "}
                  <b className="font-semibold text-ink">{rows.length}개</b>를 보고 있습니다. 나머지는
                  이름이나 상품코드로 찾을 수 있습니다.
                </p>
              ) : null}
            </>
          )}
        </ListBox>
      )}
    </div>
  );
}

/**
 * Two fail-soft reads per row, concurrent, and the list never waits for them. A row whose reads
 * failed gets `null` (rendered as a sentence, not as zeros).
 */
async function loadFacts(list: ProductSummaryView[], commit: (next: Map<string, ProductRowFacts>) => void) {
  const results = await Promise.allSettled(
    list.map(async (row) => {
      const [signals, sources] = await Promise.allSettled([
        api.getProductSignalsStrict(row.id),
        api.listProductKnowledgeSources(row.id),
      ]);
      if (signals.status !== "fulfilled") throw new Error("signals");
      const v = signals.value;
      const facts: ProductRowFacts = {
        channels: v.linkedChannels,
        inquiries: v.volume.inquiries,
        unanswered: v.volume.unansweredInquiries,
        reviews: v.volume.reviews,
        issueEvidence: v.volume.issueEvidence,
        knowledge: sources.status === "fulfilled" ? sources.value.length : null,
      };
      return [row.id, facts] as const;
    }),
  );
  const next = new Map<string, ProductRowFacts>();
  results.forEach((r, i) => {
    if (r.status === "fulfilled") next.set(r.value[0], r.value[1]);
    else next.set(list[i].id, null as unknown as ProductRowFacts);
  });
  commit(next);
}

/**
 * A catalogue whose product NAME is a bare number (a channel product id used as the title). It is the
 * real name and is not replaced; it is set in tabular figures with a muted 「코드」 mark so a column of
 * such rows reads as product objects rather than as a list of ids that lost their names.
 */
function productNameNode(name: string): React.ReactNode {
  if (/^\d{2,}$/.test(name.trim())) {
    return (
      <span className="inline-flex items-baseline gap-1.5">
        <span className="text-xs font-medium text-muted">코드</span>
        <span className="tabular-nums">{name}</span>
      </span>
    );
  }
  return name;
}
