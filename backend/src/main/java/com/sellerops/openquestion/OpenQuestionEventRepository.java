package com.sellerops.openquestion;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OpenQuestionEventRepository extends JpaRepository<OpenQuestionEvent, UUID> {

    List<OpenQuestionEvent> findByQuestionIdOrderByAtAsc(UUID questionId);
}
