#!/bin/bash
set -e

echo "=== Building NetLokator iOS 17 IPA ==="

# Auto-detect latest available Xcode in runner
LATEST_XCODE=$(ls -d /Applications/Xcode*.app 2>/dev/null | sort -V | tail -n 1)
if [ -n "$LATEST_XCODE" ]; then
    export DEVELOPER_DIR="$LATEST_XCODE/Contents/Developer"
fi

echo "Using Xcode at: $DEVELOPER_DIR"
xcodebuild -version

cd ios
echo "Generating Xcode project..."
xcodegen generate

# Ensure objectVersion is 56 (Xcode 14/15 compatible)
if [ -f "NetLokator.xcodeproj/project.pbxproj" ]; then
    sed -i '' -E 's/objectVersion = [0-9]+;/objectVersion = 56;/g' NetLokator.xcodeproj/project.pbxproj || true
fi

echo "Building Archive..."
xcodebuild archive \
  -project NetLokator.xcodeproj \
  -scheme NetLokator \
  -configuration Release \
  -destination "generic/platform=iOS" \
  -archivePath build/NetLokator.xcarchive \
  CODE_SIGNING_ALLOWED=NO \
  CODE_SIGNING_REQUIRED=NO \
  CODE_SIGN_IDENTITY=""

echo "Creating IPA..."
mkdir -p build/Payload
cp -r build/NetLokator.xcarchive/Products/Applications/NetLokator.app build/Payload/
cd build
zip -r NetLokator-unsigned.ipa Payload
echo "=== NetLokator-unsigned.ipa ready at ios/build/NetLokator-unsigned.ipa ==="
