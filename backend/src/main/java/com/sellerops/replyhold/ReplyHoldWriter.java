package com.sellerops.replyhold;

import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes and clears holds in their own transaction: a hold found by an approval or a registration that is then refused
 * must outlive the refusal, or the seller would be told why and the record would roll back with the request.
 */
@Component
class ReplyHoldWriter {

    private final ReplyDraftHoldRepository holds;

    ReplyHoldWriter(ReplyDraftHoldRepository holds) {
        this.holds = holds;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void hold(UUID orgId, ReplyDraftHold.Lane lane, UUID subjectId, int version, boolean approved,
                     String codes, String note, String basis) {
        ReplyDraftHold row = holds.findByOrgIdAndLaneAndSubjectId(orgId, lane, subjectId).orElseGet(ReplyDraftHold::new);
        row.setOrgId(orgId);
        row.setLane(lane);
        row.setSubjectId(subjectId);
        row.setDraftVersion(version);
        row.setApproved(approved);
        row.setCodes(codes);
        row.setNote(note);
        row.setBasis(basis);
        holds.save(row);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void clear(UUID orgId, ReplyDraftHold.Lane lane, UUID subjectId) {
        holds.deleteByOrgIdAndLaneAndSubjectId(orgId, lane, subjectId);
    }
}
