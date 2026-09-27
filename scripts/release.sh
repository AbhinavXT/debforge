#!/usr/bin/env bash
# Prepare a DebForge release:  scripts/release.sh 1.2.0
#
#  1. sets versionName / versionCode in app/build.gradle.kts
#  2. creates the F-Droid/GitHub changelog for that versionCode (draft from
#     commit subjects) and opens it in $EDITOR so you can tidy it
#  3. commits "Release v1.2.0" and creates tag v1.2.0
#
# Nothing is pushed; the script prints the push command at the end.
# Works with macOS's bash 3.2 and BSD tools.
set -euo pipefail

cd "$(dirname "$0")/.."
GRADLE=app/build.gradle.kts
CHANGELOGS=fastlane/metadata/android/en-US/changelogs

die() { echo "error: $*" >&2; exit 1; }

[ $# -eq 1 ] || die "usage: scripts/release.sh X.Y.Z"
VERSION="${1#v}"
[[ "$VERSION" =~ ^([0-9]+)\.([0-9]+)\.([0-9]+)$ ]] || die "version must look like 1.2.0"
MAJOR=${BASH_REMATCH[1]}; MINOR=${BASH_REMATCH[2]}; PATCH=${BASH_REMATCH[3]}
[ "$MINOR" -lt 100 ] && [ "$PATCH" -lt 100 ] || die "minor and patch must be below 100"
CODE=$((10#$MAJOR * 10000 + 10#$MINOR * 100 + 10#$PATCH))
TAG="v$VERSION"

CUR_CODE=$(sed -nE 's/^[[:space:]]*versionCode = ([0-9]+).*/\1/p' "$GRADLE" | head -n1)
[ -n "$CUR_CODE" ] || die "couldn't find versionCode in $GRADLE"
[ "$CODE" -gt "$CUR_CODE" ] || [ "$CODE" -eq "$CUR_CODE" ] || die "$VERSION ($CODE) is older than current versionCode $CUR_CODE"
git rev-parse -q --verify "refs/tags/$TAG" >/dev/null && die "tag $TAG already exists"
[ -z "$(git status --porcelain --untracked-files=no)" ] || die "commit or stash your changes first"

mkdir -p "$CHANGELOGS"
NOTES="$CHANGELOGS/$CODE.txt"
if [ ! -f "$NOTES" ]; then
    LAST=$(git describe --tags --abbrev=0 --match 'v*' 2>/dev/null || true)
    RANGE=${LAST:+$LAST..HEAD}
    # Commit subjects as a starting point, cut to fit F-Droid's 500 chars.
    git log --no-merges --pretty='• %s' ${RANGE:-HEAD} | grep -v '^• Release v' \
        | awk '{ n += length($0) + 1; if (n > 480) exit; print }' > "$NOTES" || true
    [ -s "$NOTES" ] || echo "• Bug fixes and improvements" > "$NOTES"
fi
if [ -t 0 ] && [ -t 1 ]; then
    echo "Opening $NOTES — this is the \"What's new\" text on F-Droid and GitHub."
    "${EDITOR:-vi}" "$NOTES"
fi
LEN=$(perl -CSD -0777 -ne 'print length' "$NOTES")   # characters, not bytes
[ "$LEN" -le 500 ] || die "$NOTES is $LEN characters; F-Droid allows 500. Shorten it and re-run."

# Only now touch the build file, so a failed run leaves nothing to clean up.
perl -pi -e "s/^(\s*versionCode = )\d+/\${1}$CODE/; s/^(\s*versionName = )\"[^\"]*\"/\${1}\"$VERSION\"/" "$GRADLE"

git add "$GRADLE" "$NOTES"
# Already at this version with its changelog committed (e.g. the first
# release): nothing to commit, so just tag the current commit.
git diff --cached --quiet || git commit -q -m "Release $TAG"
git tag -a "$TAG" -m "DebForge $TAG"

echo
echo "Ready: $TAG (versionCode $CODE)."
echo "Publish with:   git push && git push origin $TAG"
