package com.sellerops.agent.reply;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * <b>What a run that still failed could not fix, in the seller's words</b> (2026-10-31, first real-model round: a
 * handoff said only 「AI가 기준에 맞는 답글을 쓰지 못했어요」 while the run knew it was N9 — the seller could not see what
 * was blocked). One sentence per rule the last check failed, by a closed map; a rule this map does not know is left out
 * rather than guessed at.
 */
public final class ReplyUnmetRules {

    private static final Map<String, String> SENTENCE = Map.ofEntries(
            Map.entry("N1", "고객 개인정보가 답글에 들어가지 않게 고치지 못했어요."),
            Map.entry("N2", "연락처나 외부 링크 없이 쓰지 못했어요."),
            Map.entry("N3", "리뷰 수정·삭제 요청이나 리뷰 대가로 읽힐 표현이 남았어요."),
            Map.entry("N4", "리뷰 내용을 실제와 다르게 옮긴 표현이 남았어요."),
            Map.entry("N5", "효능·효과로 읽힐 표현이 남았어요."),
            Map.entry("N6", "근거 없는 최고·유일 표현이나 비교가 남았어요."),
            Map.entry("N7", "고객을 탓하거나 삭제·신고를 말하는 표현이 남았어요."),
            Map.entry("N8", "법보다 불리한 반품·교환 안내가 남았어요."),
            Map.entry("N9", "등록된 정책에 없는 약속(교환·반품·보상·날짜)으로 읽힐 표현을 확인하지 못했어요."),
            Map.entry("N10", "판매자가 쓴 글로 읽히지 않는 표현이 남았어요."),
            Map.entry("G1", "이 리뷰에만 맞는 답글로 쓰지 못했어요."),
            Map.entry("G2", "리뷰의 질문에 등록된 사실로 답하지 못했어요."),
            Map.entry("G3", "고객이 말한 문제를 짚어 답하지 못했어요."),
            Map.entry("G4", "고객이 이미 해 본 방법을 다시 권하는 표현이 남았어요."),
            Map.entry("G5", "등록되지 않은 사용 팁·설명이 남았어요."),
            Map.entry("G6", "칭찬 속 아쉬운 점을 다루지 못했어요."),
            Map.entry("G7", "원인 설명이 근거 없거나 남 탓으로 읽혀요."),
            Map.entry("G8", "리뷰가 말한 문제·질문 중 빠진 것이 있어요."),
            Map.entry("F1", "길이 기준을 맞추지 못했어요."),
            Map.entry(com.sellerops.attention.reply.ChannelReplyLimit.CHANNEL_LENGTH_EXCEEDED,
                    "채널이 받는 글자 수를 넘었어요."),
            Map.entry("F-STYLE", "회사가 쓰지 않는 표현이 남았어요."),
            Map.entry("BLANK", "답글이 비어 있었어요."),
            Map.entry("EVIDENCE_CHANGED", "쓰는 도중 등록된 근거가 바뀌었어요."),
            Map.entry("CRITIQUE_UNAVAILABLE", "AI 검토를 받지 못했어요."));

    private ReplyUnmetRules() {
    }

    /** The seller's sentences for these rule ids, in order, each once; F2–F6 read as one tone sentence. */
    public static List<String> sentencesFor(List<String> ruleIds) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String id : ruleIds == null ? List.<String>of() : ruleIds) {
            String rule = id == null ? "" : id.strip();
            String sentence = rule.matches("F[2-6]") ? "말투·맺음 기준을 맞추지 못했어요." : SENTENCE.get(rule);
            if (sentence != null) {
                out.add(sentence);
            }
        }
        return new ArrayList<>(out);
    }
}
