#!/usr/bin/env bash
# Whether a change touches anything a job builds from. Prints "app=true" or "app=false" (for $GITHUB_OUTPUT):
#
#   affects.sh '<extended regex of the paths that matter>'
#
# The checkout has two commits. On a pull request, that is the merge into the base, so the diff against its first parent
# is the pull request; on a push to main, it is the squash commit and its parent. When it can't tell (no parent, an
# empty diff), the change counts: a check skipped when it should have run is worse than one that ran for nothing.
# The job still runs and passes when nothing matters (a required check has to report), it only skips the heavy steps.
set -uo pipefail
if files=$(git diff --name-only HEAD^1 HEAD 2>/dev/null) && [ -n "$files" ]; then
  { echo "Changed in this commit:"; echo "$files"; } >&2
  if echo "$files" | grep -Eq "$1"; then echo "app=true"; else echo "app=false"; fi
else
  echo "Couldn't tell what changed: running." >&2
  echo "app=true"
fi
