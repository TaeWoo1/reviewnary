#!/usr/bin/env python3
"""The answers the fourth review found read as NOT_NEEDED (2026-11-01) are not answers: run `python3 test_material_need.py`."""
import unittest

from material_need import classify, problems

IDS = {"O1", "P3"}
FULL = {"need": "PRESENT", "needed": ["P3"], "useful": [], "mustNotUse": ["O1"], "missingFact": "", "reason": "r"}


class Problems(unittest.TestCase):
    def test_the_reviews_three_inputs_are_not_answers(self):
        self.assertTrue(problems({}, IDS))
        self.assertTrue(problems({**FULL, "need": "MAYBE"}, IDS))
        self.assertTrue(problems({**FULL, "needed": []}, IDS))

    def test_ids_the_store_does_not_have_and_contradictions(self):
        self.assertTrue(problems({**FULL, "needed": ["P9"]}, IDS))
        self.assertTrue(problems({**FULL, "mustNotUse": ["P3"]}, IDS))
        self.assertTrue(problems({**FULL, "need": "NONE"}, IDS))
        self.assertTrue(problems({**FULL, "need": "ABSENT", "needed": [], "missingFact": ""}, IDS))
        self.assertTrue(problems({"error": "HTTPError"}, IDS))

    def test_a_complete_answer_passes_and_is_classified(self):
        self.assertEqual(problems(FULL, IDS), [])
        self.assertEqual(classify(FULL, ["P3"]), ["FOUND"])
        none = {**FULL, "need": "NONE", "needed": [], "mustNotUse": []}
        self.assertEqual(problems(none, IDS), [])
        self.assertEqual(classify(none, []), ["NOT_NEEDED"])


if __name__ == "__main__":
    unittest.main()
