#!/usr/bin/env bash
# Whether a change touches anything a job builds from. Prints "app=true" or "app=false" (for $GITHUB_OUTPUT):
#
#   affects.sh '<extended regex of the paths that matter>'
#
# The checkout has two commits. On a pull request, that is the merge into the base, so the diff against its first parent
# is the pull request; on a push to main, it is the squash commit and its parent. When it can't tell (no parent, an
# empty diff), the change counts: a check skipped when it should have run is worse than one that ran for nothing.
# The job still runs and passes when nothing matters (a required check has to report), it only skips the heavy steps.
#
# Two details that would let a change through unseen: a file moved out of app/ shows up as a rename and --name-only
# lists only where it went, so renames are off (the removal from app/ is listed); and Git quotes and escapes a name
# like app/Árvore.java unless the list is NUL-separated (-z), so the paths are matched as NUL-terminated records.
# (No pipefail: grep -q ends early, and the git behind it getting SIGPIPE would turn a match into a failure.)
set -u
changed() { git diff --no-renames --name-only -z HEAD^1 HEAD 2>/dev/null; }
if [ "$(changed | head -c 1 | wc -c)" -gt 0 ]; then
  { echo "Changed in this commit:"; changed | tr '\0' '\n'; } >&2
  if changed | grep -zEq "$1"; then echo "app=true"; else echo "app=false"; fi
else
  echo "Couldn't tell what changed: running." >&2
  echo "app=true"
fi
