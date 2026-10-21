#!/usr/bin/env bash
# Whether a change touches anything a job builds from. Prints "app=true" or "app=false" (for $GITHUB_OUTPUT):
#
#   [AFFECTS_BASE=<sha before the push>] affects.sh '<extended regex of the paths that matter>'
#
# What is compared: on a pull request the checkout is the merge into the base, so HEAD against its first parent is the
# pull request, even when the base moved on. On a push to main, AFFECTS_BASE is the commit main was at before the push
# (github.event.before), so a push of several commits is seen whole; if that commit isn't in the checkout (or is the
# all-zero "no commit"), it can't be told, and the change counts. So it does when there is no parent or the diff is
# empty: a check skipped when it should have run is worse than one that ran for nothing.
# The job still runs and passes when nothing matters (a required check has to report), it only skips the heavy steps.
#
# Two details that would let a change through unseen: a file moved out of app/ shows up as a rename and --name-only
# lists only where it went, so renames are off (the removal from app/ is listed); and Git quotes and escapes a name
# like app/Árvore.java unless the list is NUL-separated (-z), so the paths are matched as NUL-terminated records.
# (No pipefail: grep -q ends early, and the git behind it getting SIGPIPE would turn a match into a failure.)
set -u
from="HEAD^1"
if [ -n "${AFFECTS_BASE:-}" ]; then
  if git cat-file -e "${AFFECTS_BASE}^{commit}" 2>/dev/null; then
    from="$AFFECTS_BASE"
  else
    echo "The commit before the push (${AFFECTS_BASE}) isn't in the checkout: running." >&2
    echo "app=true"
    exit 0
  fi
fi
changed() { git diff --no-renames --name-only -z "$from" HEAD 2>/dev/null; }
if [ "$(changed | head -c 1 | wc -c)" -gt 0 ]; then
  { echo "Changed since $from:"; changed | tr '\0' '\n'; } >&2
  if changed | grep -zEq "$1"; then echo "app=true"; else echo "app=false"; fi
else
  echo "Couldn't tell what changed: running." >&2
  echo "app=true"
fi
