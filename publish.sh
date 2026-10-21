#!/bin/zsh
# Publishes a new version of runwayd. Every installed copy then offers "Restart to update".
#
#   ./publish.sh 1.1.0 "Checklist can look a year ahead"
#
# What it does, in order (it stops at the first problem, before anything is pushed):
#   1. checks your setup: a clean git tree, your Developer ID certificate, saved notary
#      credentials, the GitHub CLI signed in, and a version newer than the last one
#   2. sets the version in the three pom.xml files, builds, and runs every test
#   3. builds the Mac app, signed with your Developer ID (with the native libraries inside
#      JavaFX's jars signed too, since Apple checks inside jars)
#   4. sends it to Apple for notarization (a few minutes) and staples the approval on
#   5. makes the release files: runwayd-1.1.0-mac.zip (what installed apps update from) and
#      runwayd-1.1.0.dmg (for installing on a new Mac), also signed and notarized
#   6. commits the version, tags it v1.1.0, pushes, and creates the GitHub release
#
# One-time setup: see "Releasing an update" in README.md.
set -euo pipefail

cd "$(dirname "$0")"
export JAVA_HOME=$(ls -d ~/tools/jdk-21*/Contents/Home)
export PATH="$JAVA_HOME/bin:$HOME/tools/apache-maven-3.9.9/bin:$PATH"

VERSION="${1:-}"
NOTES="${2:-runwayd $VERSION}"
NOTARY_PROFILE=runwayd-notary
OUT=desktop/target/release

fail() { echo "\n✗ $1" >&2; exit 1; }
step() { echo "\n▶ $1"; }

# ---------------------------------------------------------------------------
# 1. Check the setup before doing anything
# ---------------------------------------------------------------------------
step "Checking your setup"

[[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || fail "Usage: ./publish.sh 1.1.0 \"What changed\""
git diff --quiet && git diff --cached --quiet || fail "Commit (or put aside) your changes first; a release is built from what's committed."
[[ "$(git branch --show-current)" == "main" ]] || fail "Switch to the main branch first."
git rev-parse -q --verify "refs/tags/v$VERSION" >/dev/null && fail "v$VERSION was already released; pick a higher version."

REPO=$(sed -n 's:.*<runwayd.updateRepo>\(.*\)</runwayd.updateRepo>.*:\1:p' pom.xml)
TEAM=$(sed -n 's:.*<runwayd.teamId>\(.*\)</runwayd.teamId>.*:\1:p' pom.xml)
[[ -n "$REPO" && -n "$TEAM" ]] || fail "Fill in runwayd.updateRepo and runwayd.teamId in pom.xml first."

# The certificate's full name is "Developer ID Application: Your Name (TEAMID)"; jpackage wants
# the part after the colon.
IDENTITY=$(security find-identity -v -p codesigning | grep "Developer ID Application" | grep "($TEAM)" \
    | head -1 | sed -E 's/.*"(.*)"/\1/')
[[ -n "$IDENTITY" ]] || fail "No \"Developer ID Application\" certificate for team $TEAM in your keychain."
SIGNER="${IDENTITY#Developer ID Application: }"

xcrun notarytool history --keychain-profile "$NOTARY_PROFILE" >/dev/null 2>&1 \
    || fail "No saved notary credentials. Run: xcrun notarytool store-credentials $NOTARY_PROFILE"
gh auth status >/dev/null 2>&1 || fail "The GitHub CLI isn't signed in. Run: gh auth login"

CURRENT=$(sed -n 's:^    <version>\(.*\)</version>:\1:p' pom.xml | head -1)
NEWEST=$(printf '%s\n%s\n' "$CURRENT" "$VERSION" | sort -V | tail -1)
[[ "$NEWEST" == "$VERSION" ]] || fail "$VERSION is older than the current $CURRENT."
echo "  releasing $VERSION (was $CURRENT) to $REPO, signed by $IDENTITY"

# ---------------------------------------------------------------------------
# 2. Set the version, build and test
# ---------------------------------------------------------------------------
step "Building and testing $VERSION"
if [[ "$CURRENT" != "$VERSION" ]]; then
    sed -i '' "s#<version>$CURRENT</version>#<version>$VERSION</version>#" pom.xml core/pom.xml desktop/pom.xml
fi
mvn -q -B clean install

# ---------------------------------------------------------------------------
# 3. The signed Mac app
# ---------------------------------------------------------------------------
step "Building the signed app"
rm -rf "$OUT"
INPUT="$OUT/input"
mkdir -p "$INPUT"
mvn -q -B -pl desktop dependency:copy-dependencies -DincludeScope=runtime -DoutputDirectory="$PWD/$INPUT"
cp desktop/target/desktop-*.jar "$INPUT/desktop.jar"
[[ -z "$(find "$INPUT" -type f ! -name '*.jar')" ]] || fail "Something other than the app's jars is in $INPUT."

# JavaFX's Mac jars carry native libraries (.dylib). Apple's notary looks inside jars, so each one
# is signed, then the jar is put back together.
for jar in "$PWD/$INPUT"/javafx-*-mac-*.jar; do
    unpacked=$(mktemp -d)
    (cd "$unpacked" && unzip -q "$jar")
    find "$unpacked" -name '*.dylib' -exec codesign --force --timestamp --options runtime --sign "$IDENTITY" {} \;
    rm "$jar"
    (cd "$unpacked" && zip -q -r -X "$jar" .)
    rm -rf "$unpacked"
done

jpackage \
    --type app-image \
    --name runwayd \
    --app-version "$VERSION" \
    --input "$INPUT" \
    --main-jar desktop.jar \
    --main-class com.finance.runwayd.desktop.Launcher \
    --java-options "-Dfile.encoding=UTF-8" \
    --mac-package-identifier com.finance.runwayd \
    --mac-sign \
    --mac-signing-key-user-name "$SIGNER" \
    --mac-entitlements packaging/entitlements.plist \
    --dest "$OUT"
APP="$OUT/runwayd.app"
codesign --verify --deep --strict "$APP" || fail "The app's signature didn't check out."

# ---------------------------------------------------------------------------
# 4. Notarize the app
# ---------------------------------------------------------------------------
step "Sending the app to Apple for notarization (a few minutes)"
ditto -c -k --keepParent "$APP" "$OUT/notarize.zip"
xcrun notarytool submit "$OUT/notarize.zip" --keychain-profile "$NOTARY_PROFILE" --wait \
    | tee "$OUT/notarize.log"
grep -q "status: Accepted" "$OUT/notarize.log" || fail "Apple didn't accept it. Details: xcrun notarytool log <id> --keychain-profile $NOTARY_PROFILE"
xcrun stapler staple "$APP"
spctl --assess --type execute "$APP" || fail "macOS Gatekeeper doesn't accept the app."

# ---------------------------------------------------------------------------
# 5. The release files
# ---------------------------------------------------------------------------
step "Making the release files"
ZIP="$OUT/runwayd-$VERSION-mac.zip"
ditto -c -k --keepParent "$APP" "$ZIP"

DMG="$OUT/runwayd-$VERSION.dmg"
mkdir -p "$OUT/dmg"
cp -R "$APP" "$OUT/dmg/"
ln -s /Applications "$OUT/dmg/Applications"   # so it opens as "drag runwayd to Applications"
hdiutil create -quiet -volname runwayd -srcfolder "$OUT/dmg" -format UDZO "$DMG"
codesign --sign "$IDENTITY" --timestamp "$DMG"
xcrun notarytool submit "$DMG" --keychain-profile "$NOTARY_PROFILE" --wait | tee "$OUT/notarize-dmg.log"
grep -q "status: Accepted" "$OUT/notarize-dmg.log" || fail "Apple didn't accept the .dmg."
xcrun stapler staple "$DMG"

# ---------------------------------------------------------------------------
# 6. Commit, tag, push, release
# ---------------------------------------------------------------------------
step "Publishing v$VERSION on GitHub"
if ! git diff --quiet; then
    git commit -q -am "Release $VERSION"
fi
git tag -a "v$VERSION" -m "runwayd $VERSION"
git push -q origin main --follow-tags
gh release create "v$VERSION" "$ZIP" "$DMG" --repo "$REPO" --title "runwayd $VERSION" --notes "$NOTES"

echo "\n✓ runwayd $VERSION is out. Installed copies will offer \"Restart to update\" within a few hours,"
echo "  or right away from Help → Check for Updates."
