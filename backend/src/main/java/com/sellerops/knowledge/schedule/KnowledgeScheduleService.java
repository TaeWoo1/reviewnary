package com.sellerops.knowledge.schedule;

import com.sellerops.common.ApiException;
import com.sellerops.product.OperatorProductName;
import com.sellerops.product.Product;
import com.sellerops.product.ProductRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * <b>Inbound, outbound and production schedules as lines with fields</b> (owner decision 4, 2026-11-14): product · option,
 * kind, 예정/확정, date, what it covers, when last checked. Written through the knowledge workbook's 「입고·출고 일정」 sheet
 * and turned on or off on the knowledge screen — the existing ways a seller manages material, no new tool.
 *
 * <p>What a line may ground is fixed here, once, in {@link #evidenceText}: a 확정 date as the product's schedule, a 예정
 * date only as a plan that can move, a past date not at all, and never any one customer's shipment.
 */
@Service
public class KnowledgeScheduleService {

    private final KnowledgeScheduleRepository schedules;
    private final ProductRepository products;

    public KnowledgeScheduleService(KnowledgeScheduleRepository schedules, ProductRepository products) {
        this.schedules = schedules;
        this.products = products;
    }

    /** One line as the knowledge screen shows it. */
    public record ScheduleView(UUID id, UUID productId, String productName, String optionName, String kind,
                               String kindLabel, String status, String statusLabel, LocalDate scheduleDate,
                               String scopeNote, LocalDate lastCheckedOn, boolean active, boolean past,
                               Instant updatedAt) {
    }

    @Transactional(readOnly = true)
    public List<ScheduleView> list(UUID orgId) {
        LocalDate today = com.sellerops.knowledge.currency.KnowledgeCurrencyService.today();
        List<ScheduleView> out = new ArrayList<>();
        for (KnowledgeSchedule s : schedules.findAllByOrgIdOrderByCreatedAtAsc(orgId)) {
            out.add(view(s, nameOf(orgId, s.getProductId()), today));
        }
        return List.copyOf(out);
    }

    @Transactional
    public ScheduleView setActive(UUID orgId, UUID id, boolean active) {
        KnowledgeSchedule s = schedules.findByIdAndOrgId(id, orgId)
                .orElseThrow(() -> ApiException.notFound("일정을 찾을 수 없어요."));
        s.setActive(active);
        KnowledgeSchedule saved = schedules.save(s);
        return view(saved, nameOf(orgId, saved.getProductId()),
                com.sellerops.knowledge.currency.KnowledgeCurrencyService.today());
    }

    /** A new line, or the line {@code id} rewritten — the workbook's write path. */
    @Transactional
    public KnowledgeSchedule save(UUID orgId, UUID id, UUID productId, String optionName, KnowledgeSchedule.Kind kind,
                                  KnowledgeSchedule.Status status, LocalDate date, String scopeNote,
                                  LocalDate lastCheckedOn, boolean active, String actorName) {
        if (kind == null || status == null) {
            throw ApiException.badRequest("일정 종류(입고 · 출고 · 생산)와 예정/확정을 적어 주세요.");
        }
        if (productId != null) {
            products.findById(productId).filter(p -> orgId.equals(p.getOrgId()))
                    .orElseThrow(() -> ApiException.notFound("상품을 찾을 수 없어요."));
        }
        if (status == KnowledgeSchedule.Status.CONFIRMED && date == null) {
            throw ApiException.badRequest("확정 일정에는 날짜가 있어야 해요.");
        }
        KnowledgeSchedule row = id == null ? new KnowledgeSchedule() : schedules.findByIdAndOrgId(id, orgId)
                .orElseThrow(() -> ApiException.notFound("일정을 찾을 수 없어요."));
        row.setOrgId(orgId);
        row.setProductId(productId);
        row.setOptionName(blankToNull(optionName));
        row.setKind(kind);
        row.setStatus(status);
        row.setScheduleDate(date);
        row.setScopeNote(blankToNull(scopeNote));
        row.setLastCheckedOn(lastCheckedOn);
        row.setActive(active);
        if (id == null) {
            row.setAuthorName(actorName);
        }
        return schedules.save(row);
    }

    /**
     * What a schedule line says to a judge and a drafter — written by code from the fields, so the status, the
     * date's standing and the line's reach are stated the same way every time.
     */
    public static String evidenceText(KnowledgeSchedule s, String productName, LocalDate reference) {
        StringBuilder sb = new StringBuilder();
        sb.append(productName == null ? "전체 상품" : productName);
        if (s.getOptionName() != null && !s.getOptionName().isBlank()) {
            sb.append(" · ").append(s.getOptionName().strip());
        }
        sb.append(" — ").append(s.getKind().labelKo()).append(' ').append(s.getStatus().labelKo());
        sb.append(s.getScheduleDate() == null ? " (날짜 미정)" : " " + s.getScheduleDate() + "("
                + s.getScheduleDate().getMonthValue() + "월 " + s.getScheduleDate().getDayOfMonth() + "일)");
        if (s.getScopeNote() != null) {
            sb.append(" · 대상: ").append(s.getScopeNote());
        }
        if (s.getLastCheckedOn() != null) {
            sb.append(" · 마지막 확인 ").append(s.getLastCheckedOn());
        }
        sb.append(".\n");
        if (s.getStatus() == KnowledgeSchedule.Status.PLANNED) {
            sb.append("예정 일정이라 바뀔 수 있습니다. 확정된 날짜처럼 안내하지 않고, 예정이라는 것과 바뀔 수 있다는 것을 함께 말합니다.");
        } else {
            sb.append("확정된 상품 일정입니다.");
        }
        sb.append(" 이 일정은 상품의 일정이며, 고객 한 분의 주문이 출고·배송됐다는 사실이 아닙니다.");
        return sb.toString();
    }

    /** Whether a line can stand for now: on, and not a date already past (a past 확정 is history, not the plan). */
    public static boolean current(KnowledgeSchedule s, LocalDate reference) {
        return s.isActive() && (s.getScheduleDate() == null || reference == null
                || !s.getScheduleDate().isBefore(reference));
    }

    /** A content fingerprint of a line: a cited line edited since is a changed citation. */
    public static String fingerprint(KnowledgeSchedule s) {
        return "schedule:" + s.getStatus() + ":" + s.getScheduleDate() + ":" + Integer.toHexString(String.valueOf(
                s.getProductId() + "|" + s.getOptionName() + "|" + s.getKind() + "|" + s.getScopeNote()).hashCode());
    }

    public String nameOf(UUID orgId, UUID productId) {
        if (productId == null) {
            return null;
        }
        return products.findById(productId).filter(p -> orgId.equals(p.getOrgId()))
                .map(OperatorProductName::displayNameOrNull).orElse(null);
    }

    private static ScheduleView view(KnowledgeSchedule s, String productName, LocalDate today) {
        return new ScheduleView(s.getId(), s.getProductId(), productName, s.getOptionName(), s.getKind().name(),
                s.getKind().labelKo(), s.getStatus().name(), s.getStatus().labelKo(), s.getScheduleDate(),
                s.getScopeNote(), s.getLastCheckedOn(), s.isActive(),
                s.getScheduleDate() != null && s.getScheduleDate().isBefore(today), s.getUpdatedAt());
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.strip();
    }

    /** For callers that hold a product already. */
    public static String nameOf(Product p) {
        return p == null ? null : OperatorProductName.displayNameOrNull(p);
    }
}
