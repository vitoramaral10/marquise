"""Spanish checks: every fixed Ui.tr text has its Spanish in Es.java, and the texts built at runtime give theirs.
Run: python3 -m unittest discover .github/scripts"""
import unittest

import tr_scan


class Spanish(unittest.TestCase):
    def test_every_call_has_two_or_three_arguments(self):
        """Ui.tr(portuguese, english) or Ui.tr(portuguese, english, spanish): nothing else is a call to read."""
        for file, line, args in tr_scan.calls():
            self.assertIn(len(args), (2, 3), f"{file}:{line}: Ui.tr takes 2 or 3 arguments")

    def test_a_fixed_text_has_its_spanish_in_the_table(self):
        """A Ui.tr(pt, en) of two plain texts is looked up in Es.java by its English: it needs a line there."""
        table = tr_scan.translated()
        missing = []
        for file, line, args in tr_scan.calls():
            if len(args) != 2:
                continue
            pt, en = tr_scan.literal(args[0]), tr_scan.literal(args[1])
            if pt is not None and en is not None and en not in table:
                missing.append(f"{file}:{line}: {en[:70]!r}")
        self.assertEqual(missing, [], "add these to Es.java (English → Spanish):\n  " + "\n  ".join(missing))

    def test_a_text_built_at_runtime_gives_its_spanish_at_the_call(self):
        """A text with a number or a name in it can't be looked up: it must pass the Spanish as a third argument."""
        wrong = []
        for file, line, args in tr_scan.calls():
            if len(args) == 2 and (tr_scan.literal(args[0]) is None or tr_scan.literal(args[1]) is None):
                wrong.append(f"{file}:{line}: {args[1][:70]}")
        self.assertEqual(wrong, [], "use Ui.tr(portuguese, english, spanish) for:\n  " + "\n  ".join(wrong))

    def test_the_table_has_no_stale_or_empty_lines(self):
        """Every line of Es.java is for a text some Ui.tr still has, and says something."""
        used = set()
        for _, _, args in tr_scan.calls():
            if len(args) == 2:
                en = tr_scan.literal(args[1])
                if en is not None and tr_scan.literal(args[0]) is not None:
                    used.add(en)
        table = tr_scan.translated()
        self.assertEqual(sorted(set(table) - used), [], "no Ui.tr uses these any more: remove them from Es.java")
        self.assertEqual([k for k, v in table.items() if not v.strip()], [], "empty Spanish")

    def test_a_translation_keeps_the_numbers_and_escapes_of_the_english(self):
        """A \\n stays a \\n and the like: a line lost or added would change the layout."""
        for en, es in tr_scan.translated().items():
            self.assertEqual(en.count("\\n"), es.count("\\n"), f"line breaks differ: {en[:60]!r}")
            for sign in ("→", "←", "“", "”", "…"):
                self.assertEqual(en.count(sign) > 0, es.count(sign) > 0, f"{sign!r} differs: {en[:60]!r}")


if __name__ == "__main__":
    unittest.main()
