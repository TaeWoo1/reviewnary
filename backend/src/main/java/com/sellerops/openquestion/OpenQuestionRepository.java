package com.sellerops.openquestion;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OpenQuestionRepository extends JpaRepository<OpenQuestion, UUID> {

    List<OpenQuestion> findByOrgIdAndLaneAndSubjectIdAndStateOrderByCreatedAtAsc(UUID orgId, String lane, UUID subjectId,
                                                                               String state);

    List<OpenQuestion> findByOrgIdAndLaneAndSubjectIdOrderByCreatedAtAsc(UUID orgId, String lane, UUID subjectId);
}
