package com.sellerops.knowledge.org.dto;

import com.sellerops.knowledge.org.OrgKnowledgeType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * What the seller submits. Validation is here so an empty policy can never be indexed.
 *
 * <p>{@code customerPhrases} is 「고객은 이렇게 말해요」; null leaves the stored list as it is, an empty list clears it.
 */
public record OrgKnowledgeRequest(@NotNull OrgKnowledgeType knowledgeType,
                                  @NotBlank @Size(max = 200) String title,
                                  @NotBlank @Size(max = 40_000) String body,
                                  @Size(max = 1000) String sourceUrl,
                                  List<String> customerPhrases) {

    /** Back-compat for callers written before 「고객은 이렇게 말해요」. */
    public OrgKnowledgeRequest(OrgKnowledgeType knowledgeType, String title, String body, String sourceUrl) {
        this(knowledgeType, title, body, sourceUrl, null);
    }
}
