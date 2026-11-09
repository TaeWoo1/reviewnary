package com.sellerops.openquestion;

import com.sellerops.common.ApiException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The questions a draft left to the seller, one at a time (2026-11-01 tenth review: 「질문별로 처리하고, 해결 · 대응 불필요
 * 등의 이유와 이력을 남긴다」). Two writers only: a judgement of the whole message ({@link #replace} — the reply agent's
 * save, an inquiry draft's save) and the seller ({@link #handle}). Calls no model and reaches no channel.
 */
@Service
public class OpenQuestionService {

    public static final String REVIEW = "REVIEW";
    public static final String INQUIRY = "INQUIRY";

    /** Why the seller closed a question — closed words, so a count can say what happened to them. */
    public enum Resolution {
        /** The seller wrote the answer into the reply. */
        ANSWERED,
        /** The question needs no answer here (rhetorical, already known to the customer, out of scope). */
        NOT_NEEDED,
        /** Handled outside this reply — a call, another message. */
        ELSEWHERE,
        /** A later judgement of the whole message no longer asks it. Written by {@link #replace}, never by a person. */
        REJUDGED,
        /**
         * Not answered — made moot: the seller already carried out the action deciding it was for, and the record says so
         * (2026-11-06 thirteenth review, 「‘답변함’이 아니라 ‘조치 완료로 판단 불필요’」). Written by {@link #settle} with the
         * record named, never by a person.
         */
        SETTLED_BY_ACTION;

        public static Resolution bySeller(String value) {
            for (Resolution r : values()) {
                if (r != REJUDGED && r != SETTLED_BY_ACTION && r.name().equals(value)) {
                    return r;
                }
            }
            throw ApiException.badRequest("처리 이유를 골라 주세요(답을 덧붙임 · 답하지 않아도 됨 · 따로 처리함).");
        }
    }

    /** What the screen shows of one question. */
    public record OpenQuestionView(UUID id, String question, boolean required, String state, String resolution,
                                   String note, Instant createdAt, Instant closedAt, String basisRef) {
        static OpenQuestionView of(OpenQuestion q) {
            return new OpenQuestionView(q.getId(), q.getQuestion(), q.isRequired(), q.getState(), q.getResolution(),
                    q.getNote(), q.getCreatedAt(), q.getClosedAt(), q.getBasisRef());
        }
    }

    /** A need the seller's completed action made moot: what was asked, and the record that settled it. */
    public record Settled(String ask, String basisRef) {
    }

    private final OpenQuestionRepository questions;
    private final OpenQuestionEventRepository events;

    public OpenQuestionService(OpenQuestionRepository questions, OpenQuestionEventRepository events) {
        this.questions = questions;
        this.events = events;
    }

    /** Every question of the work, open ones first then by age — the screen's list and its history. */
    @Transactional(readOnly = true)
    public List<OpenQuestionView> list(UUID orgId, String lane, UUID subjectId) {
        List<OpenQuestion> all = new ArrayList<>(questions.findByOrgIdAndLaneAndSubjectIdOrderByCreatedAtAsc(orgId,
                lane(lane), subjectId));
        all.sort((a, b) -> Boolean.compare(!OpenQuestion.STATE_OPEN.equals(a.getState()),
                !OpenQuestion.STATE_OPEN.equals(b.getState())));
        return all.stream().map(OpenQuestionView::of).toList();
    }

    /** How many questions that hold the reply are still open. */
    @Transactional(readOnly = true)
    public long openRequired(UUID orgId, String lane, UUID subjectId) {
        return open(orgId, lane, subjectId).stream().filter(OpenQuestion::isRequired).count();
    }

    @Transactional(readOnly = true)
    public List<String> openTexts(UUID orgId, String lane, UUID subjectId) {
        return open(orgId, lane, subjectId).stream().map(OpenQuestion::getQuestion).toList();
    }

    /**
     * A new judgement of the whole message: what it lists is open, and an open question it no longer lists is closed as
     * {@link Resolution#REJUDGED}. One it still lists stays the same row — its history is not restarted. A question the
     * seller already handled is not reopened by the same words coming back.
     */
    @Transactional
    public void replace(UUID orgId, String lane, UUID subjectId, List<String> asked, boolean required, String originRef,
                        String actor) {
        String l = lane(lane);
        Set<String> wanted = new LinkedHashSet<>();
        for (String q : asked == null ? List.<String>of() : asked) {
            if (q != null && !q.isBlank()) {
                wanted.add(bounded(q.strip(), 500));
            }
        }
        Instant now = Instant.now();
        Set<String> kept = new LinkedHashSet<>();
        for (OpenQuestion q : open(orgId, l, subjectId)) {
            if (wanted.contains(q.getQuestion())) {
                kept.add(q.getQuestion());
                continue;
            }
            close(q, OpenQuestion.STATE_SUPERSEDED, Resolution.REJUDGED, null, actor, now);
        }
        Set<String> handled = new LinkedHashSet<>();
        for (OpenQuestion q : questions.findByOrgIdAndLaneAndSubjectIdOrderByCreatedAtAsc(orgId, l, subjectId)) {
            if (OpenQuestion.STATE_HANDLED.equals(q.getState())) {
                handled.add(q.getQuestion());
            }
        }
        for (String text : wanted) {
            if (kept.contains(text) || handled.contains(text)) {
                continue;
            }
            OpenQuestion q = new OpenQuestion();
            q.setId(UUID.randomUUID());
            q.setOrgId(orgId);
            q.setLane(l);
            q.setSubjectId(subjectId);
            q.setQuestion(text);
            q.setRequired(required);
            q.setState(OpenQuestion.STATE_OPEN);
            q.setOriginRef(bounded(originRef, 120));
            q.setCreatedAt(now);
            questions.save(q);
            event(q, OpenQuestionEvent.OPENED, null, null, actor, now);
        }
    }

    /**
     * Questions the seller's completed action made moot (2026-11-06 thirteenth review). An open question asking it — the
     * need's words alone, or with what was missing after 「 — 」 — is closed as {@link Resolution#SETTLED_BY_ACTION} with
     * the record named; one never opened is written already closed, so the list says what was settled and on what. Call
     * before {@link #replace}, which would otherwise close it as merely re-judged. A question the seller handled, or one
     * already settled on the same record, is left as it is.
     */
    @Transactional
    public void settle(UUID orgId, String lane, UUID subjectId, List<Settled> settled, String originRef, String actor) {
        if (settled == null || settled.isEmpty()) {
            return;
        }
        String l = lane(lane);
        Instant now = Instant.now();
        for (Settled s : settled) {
            if (s == null || s.ask() == null || s.ask().isBlank() || s.basisRef() == null || s.basisRef().isBlank()) {
                continue;
            }
            String ask = bounded(s.ask().strip(), 500);
            String basis = bounded(s.basisRef().strip(), 120);
            boolean closedOne = false;
            for (OpenQuestion q : open(orgId, l, subjectId)) {
                if (asks(q.getQuestion(), ask)) {
                    settle(q, basis, actor, now);
                    closedOne = true;
                }
            }
            if (closedOne) {
                continue;
            }
            boolean already = questions.findByOrgIdAndLaneAndSubjectIdOrderByCreatedAtAsc(orgId, l, subjectId).stream()
                    .anyMatch(q -> asks(q.getQuestion(), ask) && (OpenQuestion.STATE_HANDLED.equals(q.getState())
                            || Resolution.SETTLED_BY_ACTION.name().equals(q.getResolution())
                            && basis.equals(q.getBasisRef())));
            if (already) {
                continue;
            }
            OpenQuestion q = new OpenQuestion();
            q.setId(UUID.randomUUID());
            q.setOrgId(orgId);
            q.setLane(l);
            q.setSubjectId(subjectId);
            q.setQuestion(ask);
            q.setRequired(true);
            q.setOriginRef(bounded(originRef, 120));
            q.setCreatedAt(now);
            settle(q, basis, actor, now);
        }
    }

    /** The question as listed asks {@code ask}: the need's words, alone or followed by what was missing. */
    private static boolean asks(String question, String ask) {
        return question != null && (question.equals(ask) || question.startsWith(ask + " — "));
    }

    private void settle(OpenQuestion q, String basis, String actor, Instant at) {
        q.setState(OpenQuestion.STATE_SUPERSEDED);
        q.setResolution(Resolution.SETTLED_BY_ACTION.name());
        q.setBasisRef(basis);
        q.setClosedAt(at);
        q.setClosedBy(bounded(actor, 120));
        questions.save(q);
        OpenQuestionEvent e = new OpenQuestionEvent();
        e.setId(UUID.randomUUID());
        e.setOrgId(q.getOrgId());
        e.setQuestionId(q.getId());
        e.setAction(OpenQuestionEvent.SETTLED);
        e.setResolution(Resolution.SETTLED_BY_ACTION.name());
        e.setBasisRef(basis);
        e.setActor(bounded(actor == null ? "SYSTEM" : actor, 120));
        e.setAt(at);
        events.save(e);
    }

    /** The seller closes one question, saying why. A question already closed is not closed twice. */
    @Transactional
    public OpenQuestionView handle(UUID orgId, UUID questionId, String resolution, String note, String actor) {
        Resolution why = Resolution.bySeller(resolution);
        OpenQuestion q = questions.findById(questionId).filter(x -> x.getOrgId().equals(orgId))
                .orElseThrow(() -> ApiException.notFound("질문을 찾을 수 없어요."));
        if (!OpenQuestion.STATE_OPEN.equals(q.getState())) {
            throw ApiException.conflict("이미 처리된 질문이에요.");
        }
        close(q, OpenQuestion.STATE_HANDLED, why, note == null || note.isBlank() ? null : bounded(note.strip(), 300),
                actor, Instant.now());
        return OpenQuestionView.of(q);
    }

    private List<OpenQuestion> open(UUID orgId, String lane, UUID subjectId) {
        return questions.findByOrgIdAndLaneAndSubjectIdAndStateOrderByCreatedAtAsc(orgId, lane(lane), subjectId,
                OpenQuestion.STATE_OPEN);
    }

    private void close(OpenQuestion q, String state, Resolution why, String note, String actor, Instant at) {
        q.setState(state);
        q.setResolution(why.name());
        q.setNote(note);
        q.setClosedAt(at);
        q.setClosedBy(bounded(actor, 120));
        questions.save(q);
        event(q, OpenQuestion.STATE_HANDLED.equals(state) ? OpenQuestionEvent.HANDLED : OpenQuestionEvent.SUPERSEDED,
                why.name(), note, actor, at);
    }

    private void event(OpenQuestion q, String action, String resolution, String note, String actor, Instant at) {
        OpenQuestionEvent e = new OpenQuestionEvent();
        e.setId(UUID.randomUUID());
        e.setOrgId(q.getOrgId());
        e.setQuestionId(q.getId());
        e.setAction(action);
        e.setResolution(resolution);
        e.setNote(note);
        e.setActor(bounded(actor == null ? "SYSTEM" : actor, 120));
        e.setAt(at);
        events.save(e);
    }

    private static String lane(String lane) {
        if (REVIEW.equals(lane) || INQUIRY.equals(lane)) {
            return lane;
        }
        throw ApiException.badRequest("lane은 REVIEW 또는 INQUIRY예요.");
    }

    private static String bounded(String s, int max) {
        return s == null ? null : s.length() <= max ? s : s.substring(0, max);
    }
}
