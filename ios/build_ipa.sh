#!/bin/bash
set -euo pipefail

echo "=== Building UNSIGNED NetLokator iOS 17 IPA (Sideloadly will sign) ==="

LATEST_XCODE=$(ls -d /Applications/Xcode*.app 2>/dev/null | sort -V | tail -n 1)
if [ -n "$LATEST_XCODE" ]; then
    export DEVELOPER_DIR="$LATEST_XCODE/Contents/Developer"
fi
echo "Using Xcode at: ${DEVELOPER_DIR:-default}"
xcodebuild -version

cd ios

echo "Generating Xcode project..."
xcodegen generate

if [ -f "NetLokator.xcodeproj/project.pbxproj" ]; then
    sed -i '' -E 's/objectVersion = [0-9]+;/objectVersion = 56;/g' NetLokator.xcodeproj/project.pbxproj || true
fi

rm -rf build

echo "=== Archiving WITHOUT code signing ==="
xcodebuild archive \
  -project NetLokator.xcodeproj \
  -scheme NetLokator \
  -configuration Release \
  -destination "generic/platform=iOS" \
  -archivePath build/NetLokator.xcarchive \
  CODE_SIGNING_ALLOWED=NO \
  CODE_SIGNING_REQUIRED=NO \
  CODE_SIGN_IDENTITY="" \
  CODE_SIGN_STYLE=Manual \
  PROVISIONING_PROFILE_SPECIFIER="" \
  DEVELOPMENT_TEAM=""

APP_PATH="build/NetLokator.xcarchive/Products/Applications/NetLokator.app"
test -d "$APP_PATH"

echo "=== Packaging unsigned IPA ==="
rm -rf build/Payload
mkdir -p build/Payload
cp -R "$APP_PATH" build/Payload/
( cd build && zip -qr NetLokator.ipa Payload )
test -f build/NetLokator.ipa

echo "=== Unsigned IPA ready: ios/build/NetLokator.ipa ==="
