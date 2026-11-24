#!/usr/bin/env bash
# Releases every port as version X.Y.Z:
#   tools/release.sh 0.4.0
# Needs: main, a clean tree, up to date with origin, a "## X.Y.Z" section in CHANGELOG.md, and
# the parity check and the shared cases passing. Sets VERSION_NAME in version.env (VERSION_CODE
# goes up by one when the name changes), commits "Release X.Y.Z", tags vX.Y.Z (annotated) and
# pushes main and the tag. The tag starts .github/workflows/release.yml, which builds, signs
# and publishes the GitHub release.
# RELEASE_TRAILERS="Co-Authored-By: ..." adds trailer lines to the release commit.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."

V=${1:-}
[[ $V =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "usage: tools/release.sh X.Y.Z" >&2; exit 2; }
die() { echo "release: $*" >&2; exit 1; }

[[ $(git rev-parse --abbrev-ref HEAD) == main ]] || die "not on main"
[[ -z $(git status --porcelain) ]] || die "the working tree isn't clean"
git fetch -q origin main --tags
[[ $(git rev-parse HEAD) == $(git rev-parse origin/main) ]] || die "main isn't the same as origin/main (pull or push first)"
git rev-parse -q --verify "refs/tags/v$V" >/dev/null && die "tag v$V exists already"
grep -q "^## $V\$" CHANGELOG.md || die "CHANGELOG.md has no '## $V' section"

python3 tools/parity.py
android/test.sh | tail -1

# shellcheck disable=SC1091
source version.env
if [[ $VERSION_NAME != "$V" ]]; then
  CODE=$((VERSION_CODE + 1))
  sed -i.bak -e "s/^VERSION_NAME=.*/VERSION_NAME=$V/" -e "s/^VERSION_CODE=.*/VERSION_CODE=$CODE/" version.env
  rm -f version.env.bak
  echo "version.env: $VERSION_NAME ($VERSION_CODE) -> $V ($CODE)"
  git add version.env
  git commit -q -m "Release $V" -m "Parity: none${RELEASE_TRAILERS:+
$RELEASE_TRAILERS}"
else
  echo "version.env already says $V ($VERSION_CODE): tagging HEAD"
fi
git tag -a "v$V" -m "Broadsheet $V"
git push -q origin HEAD:main
git push -q origin "v$V"
echo "pushed main and v$V: https://github.com/VladislavEkimtcov/broadsheet/actions/workflows/release.yml"
