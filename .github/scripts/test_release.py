"""Tests for release.py: which merges release, and what a release writes. Run: python3 -m unittest discover .github/scripts"""
import contextlib
import datetime
import io
import os
import tempfile
import unittest
from unittest import mock

import release

CHANGELOG = """# Changelog

## [Unreleased]
{unreleased}
## [1.9.1] - 2026-10-20

### Fixed

- Something.
"""
MANIFEST = '<manifest android:versionCode="19" android:versionName="1.9.1">\n'


def entries(*subjects):
    return [(s, "") for s in subjects]


class Bump(unittest.TestCase):
    def test_feat_is_minor_and_fix_or_perf_is_patch(self):
        self.assertEqual(release.bump(entries("feat(home): a row")), 2)
        self.assertEqual(release.bump(entries("fix: a crash")), 1)
        self.assertEqual(release.bump(entries("perf(home): faster")), 1)
        self.assertEqual(release.bump(entries("fix: a", "feat: b", "fix: c")), 2)

    def test_a_breaking_change_is_major(self):
        self.assertEqual(release.bump(entries("feat!: new settings")), 3)
        self.assertEqual(release.bump([("fix: x", "BREAKING CHANGE: settings are reset")]), 3)

    def test_the_rest_makes_no_release(self):
        self.assertEqual(release.bump(entries("docs: roadmap", "chore: tidy", "ci: cache", "test: more")), 0)
        self.assertEqual(release.bump(entries("feat(docs): a page", "fix(ci): a step")), 0)
        self.assertEqual(release.bump(entries("Merge branch 'x'", "update stuff")), 0)


class Plan(unittest.TestCase):
    def plan(self, tag, subjects):
        with mock.patch.object(release, "last_tag", return_value=tag), \
                mock.patch.object(release, "commits", return_value=entries(*subjects)), \
                mock.patch.object(release, "manifest_version", return_value=(19, "1.9.1")):
            out = io.StringIO()
            with contextlib.redirect_stdout(out):
                release.plan()
        return dict(line.split("=", 1) for line in out.getvalue().split())

    def test_versions_go_up_from_the_last_tag(self):
        self.assertEqual(self.plan("v1.9.1", ["feat: x"]), {"release": "true", "version": "1.10.0", "code": "20"})
        self.assertEqual(self.plan("v1.9.1", ["fix: x"])["version"], "1.9.2")
        self.assertEqual(self.plan("v1.9.1", ["feat!: x"])["version"], "2.0.0")

    def test_nothing_to_release(self):
        self.assertEqual(self.plan("v1.9.1", ["docs: x"])["release"], "false")

    def test_without_a_tag_the_manifest_is_the_base(self):
        self.assertEqual(self.plan("", ["fix: x"])["version"], "1.9.2")


class Files(unittest.TestCase):
    """apply and notes, on copies of the manifest and CHANGELOG in a temporary folder."""

    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.cwd = os.getcwd()
        os.chdir(self.dir.name)
        os.makedirs("app")
        with open(release.MANIFEST, "w", encoding="utf-8") as f:
            f.write(MANIFEST)

    def tearDown(self):
        os.chdir(self.cwd)
        self.dir.cleanup()

    def write_changelog(self, unreleased):
        with open(release.CHANGELOG, "w", encoding="utf-8") as f:
            f.write(CHANGELOG.format(unreleased=unreleased))

    def read(self, path):
        with open(path, encoding="utf-8") as f:
            return f.read()

    def notes(self, version):
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            release.notes(version)
        return out.getvalue().strip()

    def test_apply_bumps_the_manifest(self):
        self.write_changelog("\n### Added\n\n- A row.\n\n")
        release.apply("1.10.0", 20)
        self.assertEqual(self.read(release.MANIFEST),
                         '<manifest android:versionCode="20" android:versionName="1.10.0">\n')

    def test_unreleased_becomes_the_version(self):
        self.write_changelog("\n### Added\n\n- A row.\n\n")
        release.apply("1.10.0", 20)
        log = self.read(release.CHANGELOG)
        today = datetime.date.today().isoformat()
        self.assertIn(f"## [Unreleased]\n\n## [1.10.0] - {today}\n\n### Added\n\n- A row.\n", log)
        self.assertEqual(self.notes("1.10.0"), "### Added\n\n- A row.")
        self.assertEqual(self.notes("1.9.1"), "### Fixed\n\n- Something.")

    def test_an_empty_unreleased_is_written_from_the_commits(self):
        self.write_changelog("\n")
        subjects = entries("feat(home): a row", "fix: a crash", "docs: roadmap", "fix(ci): a step")
        with mock.patch.object(release, "commits", return_value=subjects), \
                mock.patch.object(release, "last_tag", return_value="v1.9.1"):
            release.apply("1.10.0", 20)
        self.assertEqual(self.notes("1.10.0"), "### Added\n\n- A row.\n\n### Fixed\n\n- A crash.")

    def test_notes_of_a_missing_version_are_empty(self):
        self.write_changelog("\n")
        self.assertEqual(self.notes("9.9.9"), "")


if __name__ == "__main__":
    unittest.main()
