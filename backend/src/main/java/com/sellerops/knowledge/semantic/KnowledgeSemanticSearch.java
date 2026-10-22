package com.sellerops.knowledge.semantic;

import com.sellerops.knowledge.KnowledgeRetriever;
import com.sellerops.knowledge.KnowledgeSemantics;
import com.sellerops.knowledge.KnowledgeText;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Builds the semantic view of ONE search: this question against exactly the passages this lane has
 * already decided the seller may be shown.
 *
 * <p><b>It reads the lane's own candidate list, and that is the whole scope story.</b> Product scope,
 * organisation scope, variant applicability, retired documents, the reading filters — all of them run
 * before this is called and none of them are re-decided here. A retired manual is not in the list, so
 * it has no vector looked up and cannot be found however close it is; another company's passage was
 * never in the list at all. Semantic similarity gets a vote on which of the seller's admissible
 * passages answers the question, and no vote on which passages are admissible.
 *
 * <p><b>All or nothing.</b> If any candidate has no usable vector, this returns null and the lane
 * falls back to the lexical scorer — a lane that has not read every passage may not conclude that
 * the corpus is silent.
 *
 * <p><b>The corpus is indexed as it is searched</b> (2026-10-22). Passage vectors are cached by content, so one
 * search pays only for sentences nobody has embedded yet, at most {@link #MAX_SENTENCES} of them; the rest wait for
 * the next search, which falls back to the lexical scorer meanwhile. Before this the cap counted the whole corpus,
 * so a seller whose past answers passed 64 sentences lost semantic search for good, silently. Every fallback now
 * says why in the log (counts only), so "semantic was on but did not run" is visible.
 */
@Component
public class KnowledgeSemanticSearch {

    /**
     * The most sentences one search will embed.
     *
     * <p>A bound on what one seller's question can cost, not a quality parameter. The reference
     * deployment's largest library is far below it; a corpus above it falls back to the lexical
     * scorer rather than turning one search into a bulk upload.
     */
    static final int MAX_SENTENCES = 64;

    /**
     * The most sentences one search compares at all, cached or not — a bound on the in-memory work, far above any
     * one seller's notes, policies or past answers in the reference deployment.
     */
    static final int MAX_CORPUS_SENTENCES = 4000;

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(KnowledgeSemanticSearch.class);

    private final KnowledgeEmbeddingService embeddings;
    private final KnowledgeQuestionIntent intent;

    public KnowledgeSemanticSearch(KnowledgeEmbeddingService embeddings,
                                   KnowledgeQuestionIntent intent) {
        this.embeddings = embeddings;
        this.intent = intent;
    }

    /**
     * A search with no semantic lane — what a unit test and a context without the capability get.
     *
     * <p>Named rather than left to a null field: «this lane is not present» is a state the retriever
     * already handles, and a service that has to remember to null-check it is a service that will
     * forget once.
     */
    public static KnowledgeSemanticSearch disabled() {
        return new KnowledgeSemanticSearch(null, KnowledgeQuestionIntent.disabled());
    }

    public boolean enabledFor(UUID orgId) {
        return embeddings != null && embeddings.enabledFor(orgId);
    }

    /**
     * @param question   the question as the person wrote it. <b>Not the candidate ladder</b>: the four
     *                   shortened forms exist because the lexical absence ratio is diluted by words the
     *                   corpus lacks, and a vector has no such denominator — shortening the question
     *                   only removes what it means, and would cost one vendor call per form.
     * @return null when the capability is off for this org, when the corpus is too large to embed in
     *         one search, or when any passage or the question itself could not be embedded
     */
    public <T> KnowledgeSemantics forQuestion(UUID orgId, String question,
                                              List<KnowledgeRetriever.Candidate<T>> candidates) {
        return forQuestion(orgId, question, candidates, false);
    }

    /**
     * @param customerWritten whether a customer wrote the question — see
     *                        {@code RetrievalQuery#customerWritten()}. When true and the
     *                        retrieval-intent capability is on for this organisation, the question is
     *                        ALSO embedded as a restatement of what it needs answered, and a passage
     *                        is scored by whichever of the two phrasings comes closer.
     *                        <b>Two phrasings of one question, not two questions</b>: the corpus, the
     *                        gates and the thresholds are untouched, and with the capability off the
     *                        second vector does not exist.
     */
    public <T> KnowledgeSemantics forQuestion(UUID orgId, String question,
                                              List<KnowledgeRetriever.Candidate<T>> candidates,
                                              boolean customerWritten) {
        if (candidates.isEmpty() || question == null || question.isBlank() || !enabledFor(orgId)) {
            return null;
        }
        Map<String, List<String>> sentencesOf = new HashMap<>();
        LinkedHashSet<String> all = new LinkedHashSet<>();
        for (KnowledgeRetriever.Candidate<T> candidate : candidates) {
            String quotable = candidate.quotable();
            if (quotable == null || quotable.isBlank()) {
                return null;
            }
            List<String> sentences = KnowledgeText.comparableUnits(quotable);
            sentencesOf.put(quotable, sentences);
            all.addAll(sentences);
            if (all.size() > MAX_CORPUS_SENTENCES) {
                return fellBack(orgId, "CORPUS_TOO_LARGE", all.size(), -1);
            }
        }
        List<String> ordered = new ArrayList<>(all);
        Map<String, float[]> vectors = new HashMap<>(embeddings.cachedVectors(orgId, ordered));
        List<String> missing = ordered.stream().filter(s -> !vectors.containsKey(s)).toList();
        if (!missing.isEmpty()) {
            vectors.putAll(embeddings.vectorsFor(orgId,
                    missing.subList(0, Math.min(MAX_SENTENCES, missing.size()))));
        }
        long unseen = ordered.stream().filter(s -> !vectors.containsKey(s)).count();
        if (unseen > 0) {
            return fellBack(orgId, missing.size() > MAX_SENTENCES ? "INDEXING" : "NOT_EMBEDDED", ordered.size(),
                    unseen);
        }
        float[] asked = embeddings.questionVector(orgId, question);
        if (asked == null) {
            return fellBack(orgId, "QUESTION_NOT_EMBEDDED", ordered.size(), 0);
        }
        // The customer's own sentence is always one of the phrasings. The restatement is added when
        // there is one; a vendor that refused, a capability that is off and a sentence that asks for
        // nothing all arrive here as null and all mean the same thing: search what they wrote.
        List<float[]> phrasings = new ArrayList<>(2);
        phrasings.add(asked);
        if (customerWritten) {
            String restated = intent == null ? null : intent.intentOf(orgId, question);
            float[] alternate = restated == null ? null : embeddings.questionVector(orgId, restated);
            if (alternate != null) {
                phrasings.add(alternate);
            }
        }
        Map<String, Double> best = new HashMap<>();
        // Which sentence each passage answered WITH, so two passages can be compared on the claim
        // each of them made rather than on the subject they happen to share. The retriever's
        // background rule needs it — see KnowledgeSemantics#agreementOf.
        Map<String, String> answeredWith = new HashMap<>();
        sentencesOf.forEach((quotable, sentences) -> {
            double top = -1;
            String topSentence = null;
            for (String sentence : sentences) {
                for (float[] phrasing : phrasings) {
                    double score = Vectors.cosine(phrasing, vectors.get(sentence));
                    if (score > top) {
                        top = score;
                        topSentence = sentence;
                    }
                }
            }
            best.put(quotable, top);
            if (topSentence != null) {
                answeredWith.put(quotable, topSentence);
            }
        });
        return new KnowledgeSemantics() {
            @Override
            public OptionalDouble similarityOf(String quotable) {
                Double value = best.get(quotable);
                return value == null ? OptionalDouble.empty() : OptionalDouble.of(value);
            }

            @Override
            public OptionalDouble agreementOf(String quotableA, String quotableB) {
                String a = answeredWith.get(quotableA);
                String b = answeredWith.get(quotableB);
                if (a == null || b == null) {
                    return OptionalDouble.empty();
                }
                return OptionalDouble.of(Vectors.cosine(vectors.get(a), vectors.get(b)));
            }
        };
    }

    /** The lexical fallback, said once in the log: which org, why, and how much was or was not seen. */
    private static <T> T fellBack(UUID orgId, String reason, int sentences, long unseen) {
        log.info("knowledge semantic search fell back to lexical org={} reason={} sentences={} unseen={}", orgId,
                reason, sentences, unseen);
        return null;
    }
}
