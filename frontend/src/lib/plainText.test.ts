import { describe, expect, it } from "vitest";
import { plainText, previewText } from "./plainText";

describe("plainText", () => {
  it("removes the markup Cafe24 actually sends", () => {
    // The literal opening of the first inquiry on this org's 문의 screen.
    expect(plainText('<meta charset="utf-8">안녕하세요.\n연동 테스트 중입니다.')).toBe(
      "안녕하세요.\n연동 테스트 중입니다.",
    );
  });

  it("decodes the entities NAVER review bodies arrive with", () => {
    expect(plainText("설명에 &ldquo;두 개씩&rdquo; 이라고 &amp; 적혀")).toBe(
      "설명에 “두 개씩” 이라고 & 적혀",
    );
  });

  it("keeps paragraphs a channel expressed as tags, and collapses the empty ones", () => {
    expect(plainText("<p>첫 줄</p><p>둘째 줄</p>")).toBe("첫 줄\n둘째 줄");
    expect(plainText("한 줄<br><br><br>다음 줄")).toBe("한 줄\n\n다음 줄");
  });

  it("removes the preamble Cafe24 sends ESCAPED, which decoding used to materialize", () => {
    // The literal stored body of the two newest Cafe24 inquiries on this org's 문의 screen. The tag
    // pass cannot see it (`&lt;` is not `<`), so before this the decode pass turned it INTO
    // `<meta charset="utf-8">` and the seller read that as the first words of the question.
    expect(plainText('&lt;meta charset=&quot;utf-8&quot;&gt;교환 신청은 언제까지 가능한가요?')).toBe(
      "교환 신청은 언제까지 가능한가요?",
    );
    // Same body one line down, and the same in a row preview.
    expect(
      previewText('&lt;meta charset=&quot;utf-8&quot;&gt;상품을 받은 뒤 교환이나 반품은 언제까지 가능한가요?'),
    ).toBe("상품을 받은 뒤 교환이나 반품은 언제까지 가능한가요?");
    // Unescaped, it was already handled; both spellings now land on the same text.
    expect(plainText('<meta charset="utf-8">교환 신청은 언제까지 가능한가요?')).toBe(
      "교환 신청은 언제까지 가능한가요?",
    );
  });

  it("removes ONLY the named preamble — other escaped markup is still the author's text", () => {
    // The rule this fix had to keep: a channel that escaped its markup meant those characters to be
    // read. A blanket second strip over decoded text would have deleted all of these.
    expect(plainText("&lt;b&gt;굵게&lt;/b&gt;")).toBe("<b>굵게</b>");
    expect(plainText("주문번호 &lt;20260923-0000001&gt; 확인해주세요")).toBe(
      "주문번호 <20260923-0000001> 확인해주세요",
    );
    expect(plainText("&lt;급함&gt; 답변 부탁드립니다")).toBe("<급함> 답변 부탁드립니다");
    expect(plainText("가격이 &lt;3만원 이하였으면")).toBe("가격이 <3만원 이하였으면");
  });

  it("does not eat a word that merely starts with the preamble's name", () => {
    expect(plainText("&lt;metallic&gt; 마감이 좋아요")).toBe("<metallic> 마감이 좋아요");
    expect(plainText("metadata 항목이 비어 있어요")).toBe("metadata 항목이 비어 있어요");
  });

  it("never turns markup into markup", () => {
    expect(plainText("<script>alert(1)</script>안녕")).toBe("alert(1)안녕");
    expect(plainText("&lt;b&gt;굵게&lt;/b&gt;")).toBe("<b>굵게</b>");
  });

  it("leaves an unknown entity alone rather than guessing", () => {
    expect(plainText("&notanentity; 남음")).toBe("&notanentity; 남음");
  });

  it("is empty for empty input, never the string 'null'", () => {
    expect(plainText(null)).toBe("");
    expect(plainText(undefined)).toBe("");
    expect(plainText("   ")).toBe("");
  });

  it("previewText puts a row on one line", () => {
    expect(previewText("<p>첫 줄</p><p>둘째 줄</p>")).toBe("첫 줄 둘째 줄");
  });
});
