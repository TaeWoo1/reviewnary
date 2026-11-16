package com.sellerops.knowledge.schedule;

import com.sellerops.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/**
 * <b>One inbound, outbound or production schedule line</b> (owner decision 4, 2026-11-14): which product and option, what
 * kind, whether it is 예정 or 확정, the date, what it covers, and when it was last checked.
 *
 * <p>A schedule is about the product, never about one customer's order: 「11/18 입고 확정」 does not say any order shipped.
 */
@Getter
@Setter
@Entity
@Table(name = "knowledge_schedule")
public class KnowledgeSchedule extends BaseEntity {

    public enum Kind {
        INBOUND("입고"), OUTBOUND("출고"), PRODUCTION("생산");

        private final String labelKo;

        Kind(String labelKo) {
            this.labelKo = labelKo;
        }

        public String labelKo() {
            return labelKo;
        }
    }

    public enum Status {
        PLANNED("예정"), CONFIRMED("확정");

        private final String labelKo;

        Status(String labelKo) {
            this.labelKo = labelKo;
        }

        public String labelKo() {
            return labelKo;
        }
    }

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    /** Null: the line is about every product (회사 전체). */
    @Column(name = "product_id")
    private UUID productId;

    @Column(name = "option_name", length = 200)
    private String optionName;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 20)
    private Kind kind;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status;

    @Column(name = "schedule_date")
    private LocalDate scheduleDate;

    /** What the line covers when it is not every order: 「11월 둘째 주 주문분」. */
    @Column(name = "scope_note", length = 300)
    private String scopeNote;

    @Column(name = "last_checked_on")
    private LocalDate lastCheckedOn;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "author_name", length = 100)
    private String authorName;
}
