package com.sellerops.knowledge.inventory.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * <b>What this company knows, as a list rather than as four numbers.</b>
 *
 * <p>The knowledge screen used to print counts — 「상품 지식 10 · 운영 기준 1」 — and then show the
 * seller none of the ten and none of the one: the rules lived under 설정, the product facts lived on
 * ten different product screens, and the screen named after knowledge listed only the uploaded files.
 * This view is the list those counts were standing in for.
 *
 * <p><b>It carries no text of the knowledge itself.</b> A title, where it applies, where it came
 * from, and how it has been used — enough to decide which row to open. The body is read on the
 * screen that edits it, which is also the only screen that may change it.
 *
 * <p><b>{@code citations} is a count of stored evidence relations, nothing else.</b> See
 * {@code KnowledgeUsage}: it is not delivery, not approval, not customer reach.
 *
 * @param rules                every operating rule this company has written, ordered by topic and
 *                             then by citations — the screen lays them over the fixed topic list it
 *                             owns and draws an empty row for a topic with none, which is how a
 *                             seller sees what is NOT written
 * @param productKnowledge     product facts in the canonical order below, capped at {@link #PAGE}
 * @param productKnowledgeTotal how many exist, so the screen can say what it is not showing
 * @param products             how many products this company has
 * @param productsWithKnowledge how many of them have any product knowledge at all — the emptiness
 *                             that matters most, and the one a per-product screen can never show
 * @param orphanRuleCitations  how many ORG_POLICY citations name a rule that is not in {@code rules}
 */
public record KnowledgeInventoryView(
        List<OperatingRule> rules,
        List<ProductFact> productKnowledge,
        long productKnowledgeTotal,
        long products,
        long productsWithKnowledge,
        long orphanRuleCitations) {

    /**
     * <b>How many product facts one page carries.</b>
     *
     * <p>The same decision as {@code ProductCatalogService.CATALOG_PAGE}: a bounded page, ordered
     * over the whole corpus rather than over an arbitrary slice of it, with the total stated so the
     * screen never implies the page is everything.
     */
    public static final int PAGE = 50;

    /**
     * <b>답변이 근거로 삼은 운영 기준 가운데, 지금 목록에 없는 것들.</b>
     *
     * <p>인용은 제 출처보다 오래 산다 — 기준은 고쳐 쓰이거나 지워질 수 있고, 그것을 인용한 초안은 자기가
     * 무엇을 보고 썼는지의 기록을 그대로 지닌다({@code V91}). 그래서 이 수는 이 회사 근거의 실제 상태이고,
     * 판매자가 다른 어디에서도 볼 수 없다: {@code rules}의 모든 줄은 존재하는 기준이므로, 존재하지 않게 된
     * 기준은 줄을 남기지 않는다.
     *
     * <p><b>수일 뿐이다.</b> 어떤 기준이었는지는 말하지 않으며, 말해서도 안 된다 — evidence 행에는 읽는
     * 이가 정책 이름을 짐작해 낼 수 있는 {@code locator} 문자열이 있지만, 그렇게 되찾은 이름은 어떤 외래
     * 키도 받쳐 주지 않는 주장이다. 여기서 정직한 것은 {@code source_id}가 지금의 어떤 기준과도 맞지 않는
     * 인용이 몇 건이냐는 것뿐이다.
     */

    /** One rule the company answers by. {@code documentName} non-null ⇒ it came from a file. */
    public record OperatingRule(
            UUID id,
            String knowledgeType,
            String title,
            String documentName,
            boolean active,
            long citations,
            Instant lastUsedAt) {
    }

    /**
     * One fact the seller wrote about one product.
     *
     * <p>{@code productReachable} is whether that product has a screen to open. It is false for a
     * manufactured product: the catalogue refuses to serve one ({@code realDataOnly}), so a link to
     * it is a door that answers 404. The row still NAMES the product — the knowledge is the seller's
     * own and a row that cannot say what it is about is unreadable — but it does not offer the door.
     */
    public record ProductFact(
            UUID id,
            UUID productId,
            String productName,
            boolean productReachable,
            String sourceType,
            String title,
            String documentName,
            boolean active,
            long citations,
            Instant lastUsedAt) {
    }
}
