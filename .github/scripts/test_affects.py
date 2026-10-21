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

    def affects(self, pattern):
        """What the script prints for the last commit, as True/False."""
        out = subprocess.run(["bash", SCRIPT, pattern], cwd=self.dir.name, capture_output=True, text=True).stdout.strip()
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
