#!/bin/sh
# Copies the Java Supertext SDK from its GitHub repository into lib/supertext-sdk, with its
# package changed from com.supertext.sdk to com.supertext.magnolia.translation.internal.sdk
# (API v1 in com.supertext.magnolia.translation.internal.sdk.v1).
# Maven compiles this copy into the module's jar (build-helper-maven-plugin in pom.xml), so
# sites install nothing besides the jar, and another module or webapp that ships the SDK
# itself can never clash with it. Nothing else to run: `mvn install` picks the copy up.
#
#   scripts/vendor-sdk.sh [commit-or-branch]   (default: main)
#   SDK_DIR=/path/to/clone scripts/vendor-sdk.sh   (use a local clone as it is)
set -eu
REF="${1:-main}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

if [ -n "${SDK_DIR:-}" ]; then
    SRC="$SDK_DIR"
else
    git clone --quiet https://github.com/Supertext/Supertext-API-SDK-and-Guide "$TMP/sdk"
    git -C "$TMP/sdk" checkout --quiet "$REF"
    SRC="$TMP/sdk"
fi
COMMIT="$(git -C "$SRC" rev-parse HEAD)"
# The project's own <version>: the first one after </parent> or, without a parent, the first one.
VERSION="$(sed -n '/<artifactId>supertext-sdk<\/artifactId>/,/<\/version>/s:.*<version>\(.*\)</version>.*:\1:p' "$SRC/sdk/java/pom.xml" | head -n 1)"
if [ -z "$VERSION" ]; then
    echo "vendor-sdk: no version in sdk/java/pom.xml" >&2
    exit 1
fi

DEST="$ROOT/lib/supertext-sdk"
PKG_DIR="com/supertext/magnolia/translation/internal/sdk"
rm -rf "$DEST"
mkdir -p "$DEST/src/$PKG_DIR"
# Version-neutral parts (SdkInfo, Transport, JdkHttpTransport), API v1 in the subpackage v1 (since SDK 0.2.0)
# and translation files in the subpackage files (since SDK 0.5.0).
for sub in "" v1 files; do
    if [ -d "$SRC/sdk/java/src/main/java/com/supertext/sdk/$sub" ]; then
        mkdir -p "$DEST/src/$PKG_DIR/$sub"
        cp "$SRC"/sdk/java/src/main/java/com/supertext/sdk/$sub/*.java "$DEST/src/$PKG_DIR/$sub/"
    fi
done
cp "$SRC/LICENSE" "$DEST/LICENSE"
find "$DEST/src" -name '*.java' -exec sed -i 's/\bcom\.supertext\.sdk\b/com.supertext.magnolia.translation.internal.sdk/g' {} +
printf '%s\n' "$COMMIT" > "$DEST/COMMIT"
printf '%s\n' "$VERSION" > "$DEST/VERSION"
cat > "$DEST/README.md" <<EOT
# Supertext SDK (bundled copy)

Java part of the [Supertext SDK](https://github.com/Supertext/Supertext-API-SDK-and-Guide),
version $VERSION, commit \`$COMMIT\`, package \`com.supertext.magnolia.translation.internal.sdk\` instead of \`com.supertext.sdk\` (API v1 in \`…internal.sdk.v1\`, translation files in \`…internal.sdk.files\`).
Compiled into the module's jar (see \`build-helper-maven-plugin\` in \`pom.xml\`).

Don't edit these files: fix the SDK in its own repository, then run \`scripts/vendor-sdk.sh\`.
EOT
if grep -rqE 'com\.supertext\.sdk([^.A-Za-z]|$)' "$DEST/src"; then
    echo "vendor-sdk: unprefixed com.supertext.sdk reference left in $DEST/src" >&2
    exit 1
fi
echo "lib/supertext-sdk: Supertext SDK (Java) $VERSION from commit $COMMIT"
