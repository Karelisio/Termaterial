#!/usr/bin/env bash
# Prints the notes of a CI debug release:
#   1. the changelog - subjects of the commits since the previous debug release reachable from
#      HEAD (conventional-commit prefixes dropped, chores left out) - which the app's in-app
#      updater shows to the user, up to the marker line;
#   2. then the build details, which it does not.
#
# Usage: scripts/release-notes.sh [proot packages manifest written by fetch-proot.sh]
# Needs the full history and tags (actions/checkout with fetch-depth: 0).
set -euo pipefail

# Must match ReleaseParser.CHANGELOG_END_MARKER in the app.
MARKER='<!-- termaterial:changelog-end -->'
MAX_ENTRIES=40

previous_tag="$(git describe --tags --abbrev=0 --match 'v*-debug.*' HEAD^ 2>/dev/null || true)"
if [ -n "$previous_tag" ]; then
    range="$previous_tag..HEAD"
else
    range="HEAD"
fi

changes="$(
    git log --no-merges --format='%s' "$range" \
        | { grep -vE '^chore(\([^)]*\))?!?:' || true; } \
        | head -n "$MAX_ENTRIES" \
        | sed -E 's/^[a-z]+(\([^)]*\))?!?: *//; s/^(.)/\U\1/; s/^/- /'
)"
[ -n "$changes" ] || changes="- Maintenance interne."

branch="${GITHUB_REF_NAME:-$(git rev-parse --abbrev-ref HEAD)}"

cat <<EOF
### Nouveautés
$changes

$MARKER
APK debug généré automatiquement par la CI (branche \`$branch\`, commit $(git rev-parse HEAD)).
EOF

if [ -n "${1:-}" ] && [ -s "$1" ]; then
    cat <<EOF

Inclut proot, libtalloc et libandroid-shmem de Termux, inchangés à part quelques chaînes de leur section dynamique (voir scripts/fetch-proot.sh) - sources : https://github.com/termux/proot (GPL-2.0), https://www.samba.org/ftp/talloc/ (LGPL-3.0), https://github.com/termux/libandroid-shmem (BSD-3-Clause). Paquets utilisés :
\`\`\`
$(cat "$1")
\`\`\`
EOF
fi
