"""Tests for affects.sh: which changes make a CI job run. Run: python3 -m unittest discover .github/scripts"""
import os
import subprocess
import tempfile
import unittest

SCRIPT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "affects.sh")
BUILD = r"^(app/|build\.sh$|config\.example\.properties$|\.github/scripts/|\.github/workflows/build\.yml$)"
EMULATOR = r"^(app/|build\.sh$|config\.example\.properties$|tools/emulator_check\.sh$|\.github/workflows/emulator\.yml$|\.github/scripts/affects\.sh$)"


class Affects(unittest.TestCase):
    def setUp(self):
        """A scratch repository with one commit that has the kinds of files the project has."""
        self.dir = tempfile.TemporaryDirectory()
        self.git("init", "-q", "-b", "main")
        self.git("config", "user.email", "t@t")
        self.git("config", "user.name", "t")
        for path in ("app/A.java", "docs/index.html", "README.md", "build.sh", "tools/emulator_check.sh",
                     ".github/workflows/release.yml", ".github/workflows/build.yml", "CHANGELOG.md"):
            self.touch(path, "start")
        self.commit("base")

    def tearDown(self):
        self.dir.cleanup()

    def git(self, *args):
        """Run Git in the scratch repository and return its output."""
        return subprocess.run(["git", *args], cwd=self.dir.name, check=True, capture_output=True, text=True).stdout

    def touch(self, path, text):
        """Append a line to a file, creating it (and its folder) when needed."""
        full = os.path.join(self.dir.name, path)
        os.makedirs(os.path.dirname(full), exist_ok=True)
        with open(full, "a", encoding="utf-8") as f:
            f.write(text + "\n")

    def commit(self, message):
        """Commit everything."""
        self.git("add", "-A")
        self.git("commit", "-q", "-m", message)

    def affects(self, pattern, base=None):
        """What the script prints for the last commit (or for everything since base, as on a push), as True/False."""
        env = dict(os.environ)
        env.pop("AFFECTS_BASE", None)
        if base:
            env["AFFECTS_BASE"] = base
        out = subprocess.run(["bash", SCRIPT, pattern], cwd=self.dir.name, capture_output=True, text=True, env=env).stdout.strip()
        self.assertIn(out, ("app=true", "app=false"))
        return out == "app=true"

    def test_docs_and_other_workflows_skip_both(self):
        """Docs, the CHANGELOG, the install page and the release workflow touch nothing either job is made of."""
        self.touch("README.md", "x")
        self.touch("docs/index.html", "x")
        self.touch("CHANGELOG.md", "x")
        self.touch(".github/workflows/release.yml", "x")
        self.commit("docs")
        self.assertFalse(self.affects(BUILD))
        self.assertFalse(self.affects(EMULATOR))

    def test_the_app_runs_both(self):
        """A change in app/ runs the build and the emulator."""
        self.touch("app/A.java", "x")
        self.commit("app")
        self.assertTrue(self.affects(BUILD))
        self.assertTrue(self.affects(EMULATOR))

    def test_each_job_watches_its_own_files(self):
        """build.yml is the build's, emulator_check.sh is the emulator's."""
        self.touch(".github/workflows/build.yml", "x")
        self.commit("build workflow")
        self.assertTrue(self.affects(BUILD))
        self.assertFalse(self.affects(EMULATOR))
        self.touch("tools/emulator_check.sh", "x")
        self.commit("emulator script")
        self.assertFalse(self.affects(BUILD))
        self.assertTrue(self.affects(EMULATOR))

    def test_a_docs_pull_request_is_docs_even_if_the_base_moved_on(self):
        """On a pull request the checkout is the merge into the base: only the pull request's own changes count."""
        self.git("checkout", "-q", "-b", "pr")
        self.touch("docs/index.html", "x")
        self.commit("docs pr")
        self.git("checkout", "-q", "main")
        self.touch("app/A.java", "base moved on")
        self.commit("app change on main")
        self.git("merge", "-q", "--no-ff", "pr", "-m", "merge of the pr")
        self.assertFalse(self.affects(BUILD))
        self.assertFalse(self.affects(EMULATOR))

    def test_a_pull_request_that_touches_the_app_runs(self):
        """The same merge, with the app in the pull request."""
        self.git("checkout", "-q", "-b", "pr")
        self.touch("app/B.java", "x")
        self.commit("app pr")
        self.git("checkout", "-q", "main")
        self.touch("README.md", "base moved on")
        self.commit("docs change on main")
        self.git("merge", "-q", "--no-ff", "pr", "-m", "merge of the pr")
        self.assertTrue(self.affects(BUILD))
        self.assertTrue(self.affects(EMULATOR))

    def test_a_file_moved_out_of_the_app_runs(self):
        """A rename from app/ to docs/ lists only the destination unless renames are off: the removal must show."""
        self.git("mv", "app/A.java", "docs/A.java")
        self.commit("move out of app")
        self.assertTrue(self.affects(BUILD))
        self.assertTrue(self.affects(EMULATOR))

    def test_a_file_moved_into_docs_from_elsewhere_still_skips(self):
        """Moving a doc around is still only docs."""
        self.git("mv", "README.md", "docs/README.md")
        self.commit("move a doc")
        self.assertFalse(self.affects(BUILD))
        self.assertFalse(self.affects(EMULATOR))

    def test_names_with_accents_spaces_and_quotes_are_matched(self):
        """Git quotes such names in its default listing; each one alone in its commit must still match app/."""
        for name in ("app/Árvore.java", "app/with space.java", 'app/quote"d.java', "app/tab\there.java"):
            with self.subTest(name=name):
                self.touch(name, "x")
                self.commit("one odd name: " + name.replace("\t", " "))
                self.assertTrue(self.affects(BUILD))
                self.assertTrue(self.affects(EMULATOR))

    def test_odd_names_in_docs_skip(self):
        """And such names outside of what the jobs are made of skip as usual."""
        self.touch("docs/Árvore genealógica.md", "x")
        self.commit("odd names in docs")
        self.assertFalse(self.affects(BUILD))
        self.assertFalse(self.affects(EMULATOR))

    def test_a_path_that_only_looks_like_app_skips(self):
        """The patterns are anchored: an apps/ folder or a file ending in build.sh is not app/ or build.sh."""
        self.touch("apps/notes.md", "x")
        self.touch("docs/build.sh.md", "x")
        self.commit("lookalikes")
        self.assertFalse(self.affects(BUILD))
        self.assertFalse(self.affects(EMULATOR))

    def test_a_push_of_several_commits_is_seen_whole(self):
        """An app change followed by a docs-only commit in one push: the last commit alone says docs, the push says app."""
        before = self.git("rev-parse", "HEAD").strip()
        self.touch("app/A.java", "x")
        self.commit("app")
        self.touch("README.md", "x")
        self.commit("docs after it")
        self.assertFalse(self.affects(BUILD))
        self.assertTrue(self.affects(BUILD, base=before))
        self.assertTrue(self.affects(EMULATOR, base=before))

    def test_a_docs_only_push_skips_whatever_its_length(self):
        """Several commits, none of them touching the app."""
        before = self.git("rev-parse", "HEAD").strip()
        for i in range(3):
            self.touch("docs/index.html", str(i))
            self.commit("docs %d" % i)
        self.assertFalse(self.affects(BUILD, base=before))
        self.assertFalse(self.affects(EMULATOR, base=before))

    def test_a_push_whose_before_is_missing_runs(self):
        """The commit before the push isn't in the checkout (a shallow clone), or there is none: running is safer."""
        self.touch("README.md", "x")
        self.commit("docs")
        self.assertTrue(self.affects(BUILD, base="1" * 40))
        self.assertTrue(self.affects(BUILD, base="0" * 40))

    def test_when_it_cannot_tell_it_runs(self):
        """No parent (a first commit) or an empty diff: running is safer than skipping a check that should have run."""
        with tempfile.TemporaryDirectory() as lone:
            subprocess.run(["git", "init", "-q", "-b", "main"], cwd=lone, check=True)
            subprocess.run(["git", "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-q", "--allow-empty", "-m", "first"],
                           cwd=lone, check=True)
            out = subprocess.run(["bash", SCRIPT, BUILD], cwd=lone, capture_output=True, text=True).stdout.strip()
            self.assertEqual(out, "app=true")


if __name__ == "__main__":
    unittest.main()
