package com.sellerops.agent.quota;

/**
 * Which model seam spent the call — the ones charged against the seller's daily budget.
 *
 * <p>{@code CONVERSE} joined them with Grounded Conversation Lane v1: a conversational turn reaches a
 * model once, on the seller's behalf, exactly as a plan does. The column is {@code varchar(16)} and
 * nothing switches exhaustively over this enum, so the value is the whole change.
 *
 * <p>{@code RETRIEVE} joined them on 2026-10-31 ({@code docs/standing/reply_agent_v1.md} §8 ②): the search helpers —
 * question intent, evidence eligibility, embeddings — reached a vendor on every search and left no row.
 *
 * <p>{@code ANALYZE} joined them on 2026-11-17: reading reviews into aspects and writing a product's opportunities and
 * threats from them (review-aspect capability), pressed by the seller on the analysis screen.
 *
 * <p>{@code IMAGE} joined them on 2026-11-19: reading one 상세페이지 picture for 규격 (image-knowledge capability). It
 * reached a vendor 25 times in its first pilot run and left no row (FINDINGS B215).
 */
public enum AgentUsageKind { PLAN, JUDGE, DRAFT, CONVERSE, INVESTIGATE, INTERPRET, REPLY, TRIAGE, RETRIEVE, ANALYZE, IMAGE }
