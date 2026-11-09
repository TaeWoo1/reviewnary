package com.sellerops.replyhold;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReplyHoldConfirmationRepository extends JpaRepository<ReplyHoldConfirmation, UUID> {

    List<ReplyHoldConfirmation> findAllByOrgIdAndLaneAndSubjectIdAndDraftVersionAndApprovedAndBasisAndFindingKey(
            UUID orgId, ReplyDraftHold.Lane lane, UUID subjectId, int draftVersion, boolean approved, String basis,
            String findingKey);
}
