import { describe, expect, it } from "vitest";
import { classifyPublishError, classifySaveDraftError, REPLY_TOO_LONG } from "./inquiryPublish";

/**
 * A confirm refused for its length is a 409 like a changed draft, and must not read like one (2026-11-02): the draft
 * did not move, it is kept, and the seller only has to shorten it.
 */
describe("classifyPublishError", () => {
  const said = "답변이 이 채널의 길이 한도(1,000바이트)를 넘어요. 지금 1,020바이트예요. 줄여서 저장한 뒤 다시 보내 주세요.";

  it("shows the length, the limit and the fix when the backend names the refusal", () => {
    const info = classifyPublishError(409, { code: REPLY_TOO_LONG, message: said });
    expect(info.message).toBe(said);
    expect(info.message).not.toContain("변경");
    expect(info.shouldRefresh).toBe(false);
  });

  it("still says what to do when the sentence is missing", () => {
    const info = classifyPublishError(409, { code: REPLY_TOO_LONG });
    expect(info.message).toContain("줄여서");
    expect(info.shouldRefresh).toBe(false);
  });

  it("keeps the changed-draft reading for any other 409", () => {
    expect(classifyPublishError(409, { message: "x" }).message).toContain("변경");
    expect(classifyPublishError(409).shouldRefresh).toBe(true);
  });
});

/**
 * A refused 초안 저장 names the save, never a registration (휴대폰 실기기 점검, 2026-11-14), and never asks for the reload
 * that would seed the editor over what the seller typed.
 */
describe("classifySaveDraftError", () => {
  it.each([409, 404, 403, 500, undefined])("says the save failed and the text is kept — %s", (status) => {
    const { message } = classifySaveDraftError(status);
    expect(message.startsWith("초안을 저장하지 못했어요.")).toBe(true);
    expect(message).toContain("입력한 내용은 그대로 두었어요");
    expect(message).not.toContain("등록");
  });

  it("tells a moved draft apart from a passing failure", () => {
    expect(classifySaveDraftError(409).message).toContain("다른 곳에서 이 초안이 바뀌었어요");
    expect(classifySaveDraftError(500).message).not.toContain("바뀌었어요");
  });
});
