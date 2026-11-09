#!/usr/bin/env python3
"""The round's counts read inquiries as inquiries (2026-11-01 eighth review): run `python3 test_summarize_round.py`."""
import unittest

from summarize_round import summarize

GOOD = {"1": "질문", "2": "가", "3": {"asked": ["세탁"], "missed": []}, "4": "맞음", "5": "아니오", "6": "좋음"}


def case(i, kind, outcome=None, channel="NAVER", work=None, **run):
    cid = f"{i:08d}-0000"
    r = {"kind": kind, "id": cid, "http": 200, "seconds": 5.0, **run}
    if kind == "review":
        r.setdefault("outcome", outcome)
    c = {"id": cid, "kind": kind, "channel": channel, "work": work or ("REVIEW_REPLY" if kind == "review" else
                                                                       "NAVER_PRODUCT_QNA"),
         "did": {"outcome": outcome}}
    return r, c


def round_of(*parts, verdicts=None, replies=None, material=None):
    runs = [p[0] for p in parts]
    auto = {"cases": [p[1] for p in parts], "usage": []}
    judged = verdicts or [dict(GOOD) for _ in parts]
    inputs = [{"reply": (replies or {}).get(i, "감사합니다")} for i in range(len(parts))]
    return summarize(runs, judged, inputs, auto, material or {})


class Outcomes(unittest.TestCase):
    def test_an_inquiry_draft_is_drafted_and_a_review_saved_too(self):
        out = round_of(case(1, "review", "SAVED"), case(2, "inquiry", "DRAFT"), case(3, "inquiry", "DRAFT_ASKS_CUSTOMER"))
        self.assertEqual(out["drafted"], 3)
        self.assertEqual(out["asksCustomer"], 1)
        self.assertEqual(out["approvableAsIs"], 3)

    def test_a_first_try_and_a_rewrite_are_counted_apart(self):
        out = round_of(case(1, "inquiry", "DRAFT"), case(2, "inquiry", "DRAFT", rewrite="PASSED"),
                       case(3, "inquiry", "HELD_BY_CHECK", rewrite="REFUSED"), case(4, "inquiry", "HELD_BY_CHECK"),
                       case(5, "inquiry", "HELD_BY_CHECK", rewrite="PENDING"))
        self.assertEqual(out["rewrite"], {"draftedFirstTry": 1, "draftedAfterRewrite": 1, "refusedAfterRewrite": 1,
                                          "pendingAfterRewrite": 1, "notWritten": 0})
        self.assertEqual(out["drafted"], 2)

    def test_held_failed_and_no_basis_are_three_results(self):
        out = round_of(case(1, "inquiry", "HELD_BY_CHECK"), case(2, "inquiry", "FAILED"), case(3, "inquiry", "NO_BASIS"))
        self.assertEqual((out["drafted"], out["held"], out["failed"]), (0, 1, 1))
        self.assertEqual(out["toSeller"], {"NO_BASIS": 1})

    def test_a_partial_review_is_never_approvable_as_is(self):
        out = round_of(case(1, "review", "SAVED", openQuestions=1))
        self.assertEqual((out["partial"], out["approvableAsIs"]), (1, 0))
        self.assertEqual(out["notApprovableBecause"], {"답하지 못한 질문 남음": 1})


    def test_a_partial_inquiry_draft_is_drafted_but_never_approvable(self):
        out = round_of(case(1, "inquiry", "DRAFT_PARTIAL", openQuestions=1))
        self.assertEqual((out["drafted"], out["partial"], out["approvableAsIs"]), (1, 1, 0))
        self.assertEqual(out["notApprovableBecause"], {"답하지 못한 질문 남음": 1})


class Questions(unittest.TestCase):
    """2026-11-02: an unanswered question is left to the seller or dropped, sorted by the judge one by one."""

    def test_a_question_left_to_the_seller_is_not_missed_however_the_judge_orders_them(self):
        # The dropped one first: subtracting the seller's count by position would have thrown it away.
        sorted_ = dict(GOOD, **{"3": {"asked": ["길이", "색상", "할인"], "missed": ["색상"], "toSeller": ["할인"]}})
        out = round_of(case(1, "inquiry", "DRAFT_PARTIAL", openQuestions=1), verdicts=[sorted_])
        self.assertEqual(out["questions"], {"asked": 3, "missed": 1, "leftToSeller": 1, "byCount": 0})
        self.assertEqual(out["missedProblems"], 1)

    def test_everything_left_is_on_the_seller_list(self):
        sorted_ = dict(GOOD, **{"3": {"asked": ["길이", "할인"], "missed": [], "toSeller": ["할인"]}})
        out = round_of(case(1, "inquiry", "DRAFT_PARTIAL", openQuestions=1), verdicts=[sorted_])
        self.assertEqual((out["missedProblems"], out["questions"]["missed"]), (0, 0))

    def test_an_answer_written_before_the_list_was_shown_is_counted_apart(self):
        old = dict(GOOD, **{"3": {"asked": ["길이", "할인"], "missed": ["할인"]}})
        out = round_of(case(1, "inquiry", "DRAFT_PARTIAL", openQuestions=1), verdicts=[old])
        self.assertEqual(out["questions"], {"asked": 2, "missed": 0, "leftToSeller": 0, "byCount": 1})


class Length(unittest.TestCase):
    def test_the_review_limit_is_not_applied_to_an_inquiry(self):
        long = "가" * 400
        out = round_of(case(1, "review", "SAVED"), case(2, "inquiry", "DRAFT"), replies={0: long, 1: long})
        self.assertEqual(out["notApprovableBecause"], {"채널 글자 수 초과": 1})
        self.assertEqual((out["approvableAsIs"], out["lengthNotJudged"]), (1, 1))


class Causes(unittest.TestCase):
    def test_no_material_not_found_and_written_wrong(self):
        wrong = dict(GOOD, **{"4": "일부 다름"})
        out = round_of(case(1, "inquiry", "NO_BASIS"), case(2, "inquiry", "NO_BASIS"), case(3, "inquiry", "DRAFT"),
                       verdicts=[GOOD, GOOD, wrong],
                       material={"00000001": ["ABSENT"], "00000002": ["MISSED"], "00000003": ["FOUND"]})
        self.assertEqual(out["causes"], {"자료 없음": 1, "있었는데 못 찾음": 1, "찾고도 답을 잘못 씀": 1})
        self.assertEqual((out["toSellerAppropriate"], out["toSellerUnneeded"]), (1, 1))


if __name__ == "__main__":
    unittest.main()
